/**
 * [ModelProbe] 通用实现（docs/tech/03 §3）：对目标模型依次发 3 个最小请求——
 * 纯文本、带一个 echo 工具且 toolChoice=AUTO、带一张 8×8 内置测试图——
 * 分别判定 text / tools / vision 是否可用并记录耗时；设置页据此回填 `ModelCapabilities`。
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
import com.example.autop.llm.http.di.ProbeTempDir
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import javax.inject.Inject

public class DefaultModelProbe
    @Inject
    constructor(
        private val factories: Set<@JvmSuppressWildcards LlmProviderFactory>,
        private val configProvider: ConfigProvider,
        @param:ProbeTempDir private val imageDir: File,
        private val clock: Clock,
    ) : ModelProbe {
        override suspend fun probe(profile: ModelProfile): ProbeReport {
            val factory =
                factories.firstOrNull { it.protocolId == profile.protocol }
                    ?: return ProbeReport(fail(profile), fail(profile), fail(profile))
            val provider = factory.create(profile)
            val timeoutMs = configProvider.current()[LlmConfig.PROBE].timeoutMs
            val maxOutput = profile.maxOutputTokens
            val imageFile = File.createTempFile(PROBE_IMAGE_PREFIX, PROBE_IMAGE_SUFFIX, imageDir)
            try {
                imageFile.writeBytes(ProbePng.solidRgb8x8())
                val text = timed(timeoutMs) { provider.complete(textRequest(maxOutput)) }.toPassFail()
                val tools = timed(timeoutMs) { provider.complete(toolsRequest(maxOutput)) }.toToolsResult(text)
                val vision =
                    timed(timeoutMs) { provider.complete(visionRequest(imageFile, maxOutput)) }.toPassFail()
                return ProbeReport(text = text, tools = tools, vision = vision)
            } finally {
                imageFile.delete()
            }
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

        /** text / vision：请求成功即可用，失败即不可用。 */
        private fun Timed.toPassFail(): ProbeResult =
            ProbeResult(
                verdict = if (error == null) ProbeVerdict.SUPPORTED else ProbeVerdict.UNSUPPORTED,
                error = error,
                elapsedMs = elapsedMs,
            )

        /**
         * tools 三态判定（只有明确反证才判不支持）：
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
        // 探测误报 tools=false 后整条链路被降级到 JSON 回退。提示语与必填参数让“调用工具”
        // 成为唯一合理的回答，减少未确认。
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

        private fun visionRequest(
            imageFile: File,
            maxOutput: Int,
        ): LlmRequest =
            LlmRequest(
                system = emptyList(),
                messages =
                    listOf(
                        Msg(
                            Role.USER,
                            listOf(
                                Block.Image(imageFile.absolutePath, PROBE_IMAGE_MIME, ProbePng.SIZE, ProbePng.SIZE),
                                Block.Text(VISION_PROBE),
                            ),
                        ),
                    ),
                maxOutputTokens = maxOutput,
            )

        private companion object {
            // 三段探测文本是协议烟测载荷而非用户可编辑 prompt（见 questions.md N35）。
            const val TEXT_PROBE = "Reply with the word ok."
            const val TOOLS_PROBE = "Call the echo tool with text set to \"ok\"."
            const val VISION_PROBE = "Describe this image in one short sentence."
            const val ECHO_TOOL_NAME = "echo"
            const val ECHO_TOOL_DESC = "Echoes the given text back."
            const val ECHO_PARAM = "text"
            const val PROBE_IMAGE_PREFIX = "autop-probe-"
            const val PROBE_IMAGE_SUFFIX = ".png"
            const val PROBE_IMAGE_MIME = "image/png"
        }
    }
