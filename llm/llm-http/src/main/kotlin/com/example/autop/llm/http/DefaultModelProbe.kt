/**
 * [ModelProbe] 通用实现（docs/tech/03 §3）：依次发两个最小请求——纯文本，以及带一个 echo 工具
 * 且 toolChoice=AUTO 的请求。文本请求判定连通；工具请求判定工具调用能力（三态，见 [ProbeVerdict]）。
 *
 * 输出上限一律取 [ModelProfile.maxOutputTokens]：推理模型的思考计入输出 token，
 * 上限过小会在写出工具调用前被截断，造成“支持工具却测成不支持”的误判。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmProviderFactory
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelProbe
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.Msg
import com.example.autop.llm.ProbeReport
import com.example.autop.llm.ProbeResult
import com.example.autop.llm.ProbeVerdict
import com.example.autop.llm.Role
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import com.example.autop.llm.complete
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

public class DefaultModelProbe
    @Inject
    constructor(
        private val factories: Set<@JvmSuppressWildcards LlmProviderFactory>,
        private val configProvider: ConfigProvider,
        private val clock: Clock,
    ) : ModelProbe {
        override suspend fun probe(profile: ModelProfile): ProbeReport {
            val factory =
                factories.firstOrNull { it.protocolId == profile.protocol }
                    ?: return ProbeReport(fail(profile), fail(profile))
            val provider = factory.create(profile)
            val timeoutMs = configProvider.current()[LlmConfig.PROBE].timeoutMs
            val maxOutput = profile.maxOutputTokens
            val text = timed(timeoutMs) { provider.complete(textRequest(maxOutput)) }.toPassFail()
            val tools = timed(timeoutMs) { provider.complete(toolsRequest(maxOutput)) }.toToolsResult(text)
            return ProbeReport(text = text, tools = tools)
        }

        private fun fail(profile: ModelProfile): ProbeResult =
            ProbeResult(
                verdict = ProbeVerdict.UNSUPPORTED,
                error =
                    AppError(
                        code = LlmErrorCodes.NOT_CONFIGURED,
                        message = "no provider factory for protocol: ${profile.protocol}",
                    ),
            )

        /** 一次带超时的探测请求：结果（成功消息或错误）+ 耗时。 */
        private class Timed(
            val completed: LlmStreamEvent.Completed?,
            val error: AppError?,
            val elapsedMs: Long,
        )

        private suspend fun timed(
            timeoutMs: Long,
            block: suspend () -> Outcome<LlmStreamEvent.Completed>,
        ): Timed {
            val started = clock.nowMillis()
            var completed: LlmStreamEvent.Completed? = null
            val error =
                try {
                    withTimeout(timeoutMs) {
                        when (val outcome = block()) {
                            is Outcome.Ok -> {
                                completed = outcome.value
                                null
                            }
                            is Outcome.Err -> outcome.error
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    AppError(
                        code = LlmErrorCodes.TIMEOUT,
                        message = "probe timed out after ${timeoutMs}ms",
                        cause = e,
                    )
                } catch (e: LlmException) {
                    e.error
                }
            return Timed(completed, error, clock.nowMillis() - started)
        }

        /** 文本请求：成功即连通，失败即不可用。 */
        private fun Timed.toPassFail(): ProbeResult =
            ProbeResult(
                verdict = if (error == null) ProbeVerdict.SUPPORTED else ProbeVerdict.UNSUPPORTED,
                error = error,
                elapsedMs = elapsedMs,
            )

        /**
         * 工具三态判定（只有明确反证才判不支持）：
         * - 回复里有 echo 调用 → 支持；
         * - 文本探测成功、带工具的请求被服务以请求格式错误拒绝 → 不支持（失败只能归因于 tools 字段）；
         * - 其余（模型选择直接回答、超时、网络、5xx、限流、文本探测本身失败）→ 未确认。
         */
        private fun Timed.toToolsResult(text: ProbeResult): ProbeResult {
            val verdict =
                when {
                    completed != null && callsEchoTool(completed.message) -> ProbeVerdict.SUPPORTED
                    error?.code == LlmErrorCodes.BAD_REQUEST && text.ok -> ProbeVerdict.UNSUPPORTED
                    else -> ProbeVerdict.INCONCLUSIVE
                }
            val reason =
                when {
                    verdict == ProbeVerdict.SUPPORTED -> null
                    error != null -> error
                    else ->
                        AppError(
                            code = LlmErrorCodes.BAD_RESPONSE,
                            message = "tools probe: model answered without calling the echo tool",
                        )
                }
            return ProbeResult(verdict = verdict, error = reason, elapsedMs = elapsedMs)
        }

        private fun callsEchoTool(message: Msg): Boolean =
            message.blocks.filterIsInstance<Block.ToolCall>().any { it.name == ECHO_TOOL_NAME }

        private fun textRequest(maxOutput: Int): LlmRequest =
            LlmRequest(
                system = emptyList(),
                messages = listOf(Msg(Role.USER, listOf(Block.Text(TEXT_PROBE)))),
                maxOutputTokens = maxOutput,
            )

        // toolChoice 与运行时请求同为 AUTO：REQUIRED 会被部分兼容代理直接拒绝，
        // 误报不支持后整条链路会被切到文本回退。提示语与必填参数让“调用工具”成为唯一合理的回答。
        private fun toolsRequest(maxOutput: Int): LlmRequest =
            LlmRequest(
                system = emptyList(),
                messages = listOf(Msg(Role.USER, listOf(Block.Text(TOOLS_PROBE)))),
                tools =
                    listOf(
                        ToolSchema(name = ECHO_TOOL_NAME, description = ECHO_TOOL_DESC, parameters = echoParams()),
                    ),
                toolChoice = ToolChoice.AUTO,
                maxOutputTokens = maxOutput,
            )

        private fun echoParams(): JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject(ECHO_PARAM) { put("type", "string") }
                }
                put("required", buildJsonArray { add(ECHO_PARAM) })
            }

        private companion object {
            // 探测文本是协议烟测载荷而非用户可编辑 prompt（见 questions.md N35）。
            const val TEXT_PROBE = "Reply with the word ok."
            const val TOOLS_PROBE = "Call the echo tool with text set to \"ok\"."
            const val ECHO_TOOL_NAME = "echo"
            const val ECHO_TOOL_DESC = "Echoes the given text back."
            const val ECHO_PARAM = "text"
        }
    }
