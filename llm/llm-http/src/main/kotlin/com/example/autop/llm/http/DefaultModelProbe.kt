/**
 * [ModelProbe] 通用实现（docs/tech/03 §3）：对目标模型依次发 3 个最小请求——
 * 纯文本、带一个 echo 工具且 toolChoice=AUTO、带一张 8×8 内置测试图——
 * 分别判定 text / tools / vision 是否可用并记录耗时；设置页据此回填 `ModelCapabilities`。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
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
import com.example.autop.llm.Role
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import com.example.autop.llm.complete
import com.example.autop.llm.http.di.ProbeTempDir
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject

public class DefaultModelProbe
    @Inject
    constructor(
        private val factories: Set<@JvmSuppressWildcards LlmProviderFactory>,
        private val configProvider: ConfigProvider,
        @param:ProbeTempDir private val imageDir: File,
    ) : ModelProbe {
        override suspend fun probe(profile: ModelProfile): ProbeReport {
            val factory =
                factories.firstOrNull { it.protocolId == profile.protocol }
                    ?: return ProbeReport(fail(profile), fail(profile), fail(profile))
            val provider = factory.create(profile)
            val timeoutMs = configProvider.current()[LlmConfig.PROBE].timeoutMs
            return ProbeReport(
                text = runProbe(timeoutMs) { provider.complete(textRequest()) },
                tools = runProbe(timeoutMs) { provider.complete(toolsRequest()) },
                vision = runProbe(timeoutMs) { provider.complete(visionRequest()) },
            )
        }

        private fun fail(profile: ModelProfile): ProbeResult =
            ProbeResult(
                ok = false,
                error =
                    AppError(
                        code = LlmErrorCodes.NOT_CONFIGURED,
                        message = "no provider factory for protocol: ${profile.protocol}",
                    ),
            )

        private suspend fun runProbe(
            timeoutMs: Long,
            block: suspend () -> Outcome<LlmStreamEvent.Completed>,
        ): ProbeResult {
            val started = System.nanoTime()
            val error =
                try {
                    withTimeout(timeoutMs) {
                        when (val outcome = block()) {
                            is Outcome.Ok -> null
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
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
            return ProbeResult(ok = error == null, error = error, elapsedMs = elapsedMs)
        }

        private fun textRequest(): LlmRequest =
            LlmRequest(
                system = emptyList(),
                messages = listOf(Msg(Role.USER, listOf(Block.Text(TEXT_PROBE)))),
                maxOutputTokens = PROBE_MAX_OUTPUT_TOKENS,
            )

        // toolChoice 与运行时请求同为 AUTO：REQUIRED 会被部分兼容代理直接拒绝，
        // 探测误报 tools=false 后整条链路被降级到 JSON 回退。
        private fun toolsRequest(): LlmRequest =
            LlmRequest(
                system = emptyList(),
                messages = listOf(Msg(Role.USER, listOf(Block.Text(TOOLS_PROBE)))),
                tools =
                    listOf(
                        ToolSchema(
                            name = ECHO_TOOL_NAME,
                            description = ECHO_TOOL_DESC,
                            parameters = JsonObject(emptyMap()),
                        ),
                    ),
                toolChoice = ToolChoice.AUTO,
                maxOutputTokens = PROBE_MAX_OUTPUT_TOKENS,
            )

        private fun visionRequest(): LlmRequest {
            val file = File.createTempFile(PROBE_IMAGE_PREFIX, PROBE_IMAGE_SUFFIX, imageDir)
            file.writeBytes(ProbePng.solidRgb8x8())
            return LlmRequest(
                system = emptyList(),
                messages =
                    listOf(
                        Msg(
                            Role.USER,
                            listOf(
                                Block.Image(file.absolutePath, PROBE_IMAGE_MIME, ProbePng.SIZE, ProbePng.SIZE),
                                Block.Text(VISION_PROBE),
                            ),
                        ),
                    ),
                maxOutputTokens = PROBE_MAX_OUTPUT_TOKENS,
            )
        }

        private companion object {
            // 三段探测文本是协议烟测载荷而非用户可编辑 prompt（见 questions.md N35）。
            const val TEXT_PROBE = "Reply with the word ok."
            const val TOOLS_PROBE = "Echo this message."
            const val VISION_PROBE = "Describe this image in one short sentence."
            const val ECHO_TOOL_NAME = "echo"
            const val ECHO_TOOL_DESC = "Echoes the input back."
            const val PROBE_IMAGE_PREFIX = "autop-probe-"
            const val PROBE_IMAGE_SUFFIX = ".png"
            const val PROBE_IMAGE_MIME = "image/png"
            const val PROBE_MAX_OUTPUT_TOKENS = 32
            const val NANOS_PER_MILLISECOND = 1_000_000L
        }
    }
