/**
 * 单个调用的执行链（docs/tech/04 §3.3）：前置校验 → 读写锁 → 超时与
 * 异常转换 → 策略两步检查（ScreenActionTool）；从 ToolRouter 拆出。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.PauseReason
import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolAuditSink
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.PolicyConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyErrorCodes
import com.example.autop.policy.PolicyOverrides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 调用执行器（每次 Run 一个）。 */
internal class ToolExecutor(
    tools: List<Tool>,
    private val policy: PolicyEngine,
    private val audit: ToolAuditSink?,
    prompts: PromptRepository,
    private val clock: Clock,
) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }
    private val screenLock = ReadWriteMutex()
    private val memoryLock = ReadWriteMutex()
    private val json = Json { ignoreUnknownKeys = true }
    val errors = ToolErrors(prompts)

    /** 执行一个调用：未知工具/参数不合法/前置失败直接出错误结果。 */
    suspend fun runOne(
        call: Block.ToolCall,
        ctx: ToolContext,
        screenWriteFailed: Boolean,
    ): ToolCallResult {
        val tool = byName[call.name]
        val parsed = parseArgs(call)
        val validated =
            if (tool != null && parsed != null) ArgsValidator.validate(tool.specFor(ctx).parameters, parsed) else null
        val rejected = reject(tool, parsed, validated, call, screenWriteFailed)
        if (rejected != null) return done(call, rejected)
        val start = clock.nowMillis()
        return ToolCallResult(call, executeLocked(tool!!, validated!!.args!!, ctx), clock.nowMillis() - start)
    }

    /**
     * 回放执行（docs/tech/04 §3.12）：跳过 schema 校验（回放参数是历史录制的
     * 定位器改写形态），锁、超时与策略检查与普通调用相同。
     */
    suspend fun runReplay(
        call: Block.ToolCall,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolCallResult {
        val tool = byName[call.name] ?: return done(call, errors.unknownTool(call.name, byName.keys.toList()))
        val start = clock.nowMillis()
        return ToolCallResult(call, executeLocked(tool, args, ctx), clock.nowMillis() - start)
    }

    /** 前置拒绝：未知工具 / 参数不合法 / 前一屏幕写入失败而跳过。 */
    private fun reject(
        tool: Tool?,
        parsed: JsonObject?,
        validated: ArgsValidation?,
        call: Block.ToolCall,
        screenWriteFailed: Boolean,
    ): ToolOutput? =
        when {
            tool == null -> errors.unknownTool(call.name, byName.keys.toList())
            parsed == null -> errors.invalidArgsJson()
            validated?.args == null -> errors.invalidArgs(validated?.errors.orEmpty())
            tool.access == ToolAccess.WRITE_SCREEN && screenWriteFailed -> errors.skipped()
            else -> null
        }

    /** 加锁 + 超时执行；异常转 agent.tool_crashed（取消原样传播）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 统一兜底转换为错误结果，异常不外传
    private suspend fun executeLocked(
        tool: Tool,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput {
        val timeoutMs = ctx.config[AgentConfig.TOOLS].let { it.timeoutMs[tool.id] ?: it.defaultTimeoutMs }
        return try {
            withTimeout(timeoutMs) {
                when (tool.access) {
                    ToolAccess.READ_SCREEN -> screenLock.withRead { invokeTool(tool, args, ctx) }
                    ToolAccess.WRITE_SCREEN -> screenLock.withWrite { invokeTool(tool, args, ctx) }
                    ToolAccess.READ_MEMORY -> memoryLock.withRead { invokeTool(tool, args, ctx) }
                    ToolAccess.WRITE_MEMORY -> memoryLock.withWrite { invokeTool(tool, args, ctx) }
                    else -> invokeTool(tool, args, ctx)
                }
            }
        } catch (e: TimeoutCancellationException) {
            errors.errorOutput(AgentErrorCodes.TOOL_TIMEOUT, "tool '${tool.id}' timed out after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.errorOutput(AgentErrorCodes.TOOL_CRASHED, e.message ?: e.toString())
        }
    }

    /** 普通工具直接 invoke；ScreenActionTool 走 prepare → 策略 → perform。 */
    private suspend fun invokeTool(
        tool: Tool,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput =
        if (tool !is ScreenActionTool) {
            tool.invoke(args, ctx)
        } else {
            when (val prepared = tool.prepare(args, ctx)) {
                is Outcome.Ok -> performScreenAction(tool, prepared.value, ctx)
                is Outcome.Err -> errors.errorOutput(prepared.error.code, prepared.error.message, prepared.error.hint)
            }
        }

    /** 策略两步检查：Block → policy.blocked + 暂停；Allow → perform。 */
    private suspend fun performScreenAction(
        tool: ScreenActionTool,
        action: PreparedAction,
        ctx: ToolContext,
    ): ToolOutput {
        val section = ctx.config[PolicyConfig.POLICY]
        val decision = policy.evaluate(action.subject, PolicyOverrides(section.overrides.allowCategories.toSet()))
        audit?.record(ctx.runId, tool.id, action.subject.targetText, decision, null)
        if (decision is PolicyDecision.Block) {
            val hint = errors.render(ID_BLOCKED_HINT, mapOf("category" to decision.category))
            val output =
                errors
                    .errorOutput(PolicyErrorCodes.POLICY_BLOCKED, "blocked by rule ${decision.ruleId}", hint)
                    .copy(control = ToolControl.Pause(PauseReason.POLICY_BLOCKED, decision.ruleId))
            audit?.record(ctx.runId, tool.id, action.subject.targetText, decision, false)
            return output
        }
        val output = tool.perform(action, ctx)
        audit?.record(ctx.runId, tool.id, action.subject.targetText, PolicyDecision.Allow, output.ok)
        return output
    }

    /** 解析失败一律转 invalid_args_json。 */
    private fun parseArgs(call: Block.ToolCall): JsonObject? =
        runCatching { json.parseToJsonElement(call.argsJson.ifEmpty { "{}" }) as? JsonObject }.getOrNull()

    private fun done(
        call: Block.ToolCall,
        output: ToolOutput,
    ): ToolCallResult = ToolCallResult(call, output, 0)

    private companion object {
        const val ID_BLOCKED_HINT = "agent.policy.blocked_hint"
    }
}
