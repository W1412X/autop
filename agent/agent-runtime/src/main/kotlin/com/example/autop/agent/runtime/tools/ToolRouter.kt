/**
 * 工具路由（docs/tech/04 §3.3）：注册检查、可见性过滤、连续可并行调用的
 * 并发分批、结果事件与 enricher 注入。单个调用的执行链见 ToolExecutor。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.ReplayableAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEnricher
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.ToolSchemaBuilder
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.ToolSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 批次内跳过原因（WP5.2）。 */
internal enum class SkipReason { AFTER_CONTROL, PREVIOUS_FAILED }

/** 一个调用的执行结果（与 calls 同序）。 */
internal data class ToolCallResult(
    val call: Block.ToolCall,
    val output: ToolOutput,
    val durationMs: Long,
)

/** 工具路由器（每次 Run 一个；工具集合按 Run 过滤后传入）。 */
internal class ToolRouter(
    private val tools: List<Tool>,
    private val executor: ToolExecutor,
    private val enrichers: List<ToolResultEnricher>,
    private val prompts: PromptRepository,
) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }
    private val byId: Map<String, Tool> = tools.associateBy { it.id }

    /** 注册检查（启动即失败，docs/tech/04 §3.3）。 */
    init {
        require(tools.map { it.id }.distinct().size == tools.size) { "tool id 重复" }
        require(tools.map { it.spec.name }.distinct().size == tools.size) { "tool name 重复" }
        require(tools.none { it.access == ToolAccess.WRITE_SCREEN && it !is ScreenActionTool }) {
            "WRITE_SCREEN 工具必须实现 ScreenActionTool"
        }
    }

    /** 发给模型的工具 schema（按 Run 上下文生成动态枚举与说明）。 */
    fun schemas(ctx: ToolContext): List<ToolSchema> = tools.map { ToolSchemaBuilder.build(it.specFor(ctx), prompts) }

    /** 按 spec.name 找工具（熔断观察等用）。 */
    fun toolFor(name: String): Tool? = byName[name]

    /**
     * 按模型可见名称执行一次调用（流程等内部调用方，docs/design/flows.md §8.2）：
     * 与普通调用同一条执行链（schema 校验、锁、超时、策略、审计），不触发 enricher。
     */
    suspend fun invokeByName(
        callId: String,
        toolName: String,
        args: kotlinx.serialization.json.JsonObject,
        ctx: ToolContext,
    ): ToolOutput = executor.runOne(Block.ToolCall(callId, toolName, args.toString()), ctx).output

    /** 按工具 id 找模型可见名称（回放合成消息用）。 */
    fun toolNameOf(toolId: String): String? = byId[toolId]?.spec?.name

    /** 本次可见工具的 id。 */
    fun toolIds(): List<String> = tools.map { it.id }

    /**
     * 执行一批工具调用：连续的可并行调用并发执行，WRITE_*、READ_SCREEN 与 CONTROL 串行；
     * 每个结果写 ToolResultEvent（[emitResult]），enricher 注入块合并后写一条 INJECTED（[emitInjected]）。
     * 控制结果之后的调用、写类失败之后的非只读调用跳过（[SkipReason]，WP5.2）；
     * [beforeEach] 在每个调用执行前调用（并行段在段开始前调用一次）。
     */
    suspend fun execute(
        calls: List<Block.ToolCall>,
        ctx: ToolContext,
        emitResult: suspend (ToolResultEvent) -> Unit,
        emitInjected: suspend (List<Block>) -> Unit,
        beforeEach: suspend () -> Unit = {},
    ): List<ToolOutput> {
        val results = arrayOfNulls<ToolCallResult>(calls.size)
        var skip: SkipReason? = null
        var i = 0
        while (i < calls.size) {
            val parallelRun = byName[calls[i].name]?.let(::isParallel) == true
            if (parallelRun) {
                var end = i
                while (end < calls.size && byName[calls[end].name]?.let(::isParallel) == true) end++
                val snapshot = skip
                beforeEach()
                coroutineScope {
                    (i until end)
                        .map { idx ->
                            async {
                                val r = runBatchCall(calls[idx], ctx, snapshot)
                                results[idx] = r
                                emitResult(toEvent(r))
                            }
                        }.awaitAll()
                }
                i = end
            } else {
                beforeEach()
                val r = runBatchCall(calls[i], ctx, skip)
                results[i] = r
                emitResult(toEvent(r))
                if (skip == null) skip = skipAfter(r.output, byName[calls[i].name])
                i++
            }
        }
        val outputs = results.filterNotNull()
        val injected = enrich(outputs, ctx)
        if (injected.isNotEmpty()) emitInjected(injected)
        return results.map { it?.output ?: executor.errors.missingOutput() }
    }

    /** 单个批次调用：命中跳过规则时不进 executor。 */
    private suspend fun runBatchCall(
        call: Block.ToolCall,
        ctx: ToolContext,
        skip: SkipReason?,
    ): ToolCallResult {
        val tool = byName[call.name]
        val reason = skipFor(skip, tool)
        if (reason != null) return ToolCallResult(call, executor.errors.skipped(reason), 0L)
        return executor.runOne(call, ctx)
    }

    /** 跳过判定：控制之后全部跳过；写类失败之后只放行只读（未知工具按非只读处理）。 */
    private fun skipFor(
        skip: SkipReason?,
        tool: Tool?,
    ): SkipReason? =
        when {
            skip == SkipReason.AFTER_CONTROL -> SkipReason.AFTER_CONTROL
            skip == SkipReason.PREVIOUS_FAILED && !isReadOnly(tool) -> SkipReason.PREVIOUS_FAILED
            else -> null
        }

    /** 执行后的跳过标记：控制结果全部跳过；写类失败后跳过非只读。 */
    private fun skipAfter(
        output: ToolOutput,
        tool: Tool?,
    ): SkipReason? =
        when {
            output.control != null -> SkipReason.AFTER_CONTROL
            !output.ok && isWrite(tool) -> SkipReason.PREVIOUS_FAILED
            else -> null
        }

    private fun isReadOnly(tool: Tool?): Boolean =
        tool != null &&
            (
                tool.access == ToolAccess.READ_SCREEN ||
                    tool.access == ToolAccess.READ_MEMORY ||
                    tool.access == ToolAccess.READ_SYSTEM
            )

    private fun isWrite(tool: Tool?): Boolean =
        tool != null &&
            (
                tool.access == ToolAccess.WRITE_SCREEN ||
                    tool.access == ToolAccess.WRITE_MEMORY ||
                    tool.access == ToolAccess.WRITE_SYSTEM
            )

    /**
     * 回放一步（docs/tech/04 §3.12）：按工具 id 找工具，参数用录制时的
     * 定位器改写形态，跳过 schema 校验执行（锁与审计照常）。
     */
    suspend fun executeReplay(
        callId: String,
        action: ReplayableAction,
        ctx: ToolContext,
    ): ToolCallResult {
        val tool = byId[action.toolId]
        val call =
            Block.ToolCall(
                id = callId,
                name = tool?.spec?.name ?: action.toolId,
                argsJson = action.replayArgs.toString(),
            )
        return executor.runReplay(call, action.replayArgs, ctx)
    }

    /**
     * 只有 READ_MEMORY/READ_SYSTEM 可并行；READ_SCREEN 串行——observe/wait/screenshot
     * 读写本 Run 的观测历史，必须有确定顺序（WP5.2）；WRITE_* 与 CONTROL 串行。
     */
    private fun isParallel(tool: Tool): Boolean =
        tool.access == ToolAccess.READ_MEMORY || tool.access == ToolAccess.READ_SYSTEM

    private suspend fun enrich(
        outputs: List<ToolCallResult>,
        ctx: ToolContext,
    ): List<Block> =
        outputs.flatMap { result ->
            val toolId = byName[result.call.name]?.id ?: result.call.name
            enrichers.flatMap { it.enrich(toolId, result.output, ctx) }
        }

    private fun toEvent(result: ToolCallResult): ToolResultEvent {
        val output = result.output
        return ToolResultEvent(
            callId = result.call.id,
            toolId = byName[result.call.name]?.id ?: result.call.name,
            ok = output.ok,
            blocks = output.content,
            data = output.data,
            errorCode = output.error?.code,
            observation = output.observation,
            durationMs = result.durationMs,
            fullBlocks = output.fullContent,
            replay = output.replay,
            screenDelta = output.screenDelta,
        )
    }
}
