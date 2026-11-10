/**
 * 调试页的工具执行器（docs/tech/08 §4.6"工具试执行"）：
 * [ScreenActionTool] 走 prepare → 策略检查 → perform（docs/tech/04 §3.3 步骤 5 的
 * `policy.evaluate` 语义：Block → `policy.blocked` + Pause(POLICY_BLOCKED)），其余工具直接 invoke。
 * WP6a 的 ToolRouter 会接管正式执行路径，本执行器只服务调试页。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.PauseReason
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.PolicyConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyErrorCodes
import com.example.autop.policy.PolicyOverrides
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DebugToolExecutor
    @Inject
    constructor(
        private val tools: Set<@JvmSuppressWildcards Tool>,
        private val policy: PolicyEngine,
        private val prompts: PromptRepository,
        private val logger: Logger,
    ) {
        /** 可试执行的工具（按 id 排序）。 */
        fun toolOptions(): List<Tool> = tools.sortedBy { it.id }

        /** 执行一个工具调用（含策略检查与崩溃兜底）。 */
        suspend fun execute(
            toolId: String,
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val tool = tools.firstOrNull { it.id == toolId }
            if (tool == null) {
                return errorOutput(AppError(code = AgentErrorCodes.UNKNOWN_TOOL, message = "tool not found: $toolId"))
            }
            return try {
                if (tool is ScreenActionTool) {
                    performScreenAction(tool, args, ctx)
                } else {
                    tool.invoke(args, ctx)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.e(tag = TAG, event = "debug_tool_crashed", "tool" to toolId, error = e)
                errorOutput(
                    AppError(
                        code = AgentErrorCodes.TOOL_CRASHED,
                        message = e.message ?: e::class.simpleName.orEmpty(),
                        cause = e,
                    ),
                )
            }
        }

        private suspend fun performScreenAction(
            tool: ScreenActionTool,
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val prepared =
                when (val got = tool.prepare(args, ctx)) {
                    is Outcome.Err -> return errorOutput(got.error)
                    is Outcome.Ok -> got.value
                }
            return when (val decision = policy.evaluate(prepared.subject, overridesOf(ctx.config), ctx.config)) {
                PolicyDecision.Allow -> tool.perform(prepared, ctx)
                is PolicyDecision.Block -> blockedOutput(decision)
            }
        }

        /** 策略拦截结果（docs/tech/04 §3.3：error(policy.blocked, hint=agent.policy.blocked_hint) + Pause）。 */
        private fun blockedOutput(decision: PolicyDecision.Block): ToolOutput {
            val hint = rendered(PROMPT_BLOCKED_HINT, mapOf("category" to decision.category))
            val text =
                rendered(
                    PROMPT_ERROR_RESULT,
                    mapOf(
                        "code" to PolicyErrorCodes.POLICY_BLOCKED,
                        "message" to "blocked by rule ${decision.ruleId}",
                        "hint" to hint,
                    ),
                )
            return ToolOutput(
                ok = false,
                content = listOf(Block.Text(text)),
                error = AppError(code = PolicyErrorCodes.POLICY_BLOCKED, message = decision.ruleId, hint = hint),
                control = ToolControl.Pause(PauseReason.POLICY_BLOCKED, decision.ruleId),
            )
        }

        private fun errorOutput(error: AppError): ToolOutput {
            val text =
                rendered(
                    PROMPT_ERROR_RESULT,
                    mapOf("code" to error.code, "message" to error.message, "hint" to (error.hint ?: "")),
                )
            return ToolOutput(ok = false, content = listOf(Block.Text(text)), error = error)
        }

        private fun rendered(
            id: String,
            vars: Map<String, String>,
        ): String = (prompts.render(id, vars) as? Outcome.Ok)?.value?.text ?: ""

        /** Task 级策略覆盖（docs/tech/04 §2.3：来自 `policy.overrides` 配置）。 */
        private fun overridesOf(config: ConfigSnapshot): PolicyOverrides =
            PolicyOverrides(allowCategories = config[PolicyConfig.POLICY].overrides.allowCategories.toSet())

        private companion object {
            const val TAG = "DebugToolExecutor"
            const val PROMPT_ERROR_RESULT = "agent.tool.error_result"
            const val PROMPT_BLOCKED_HINT = "agent.policy.blocked_hint"
        }
    }
