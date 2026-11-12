/**
 * 录制流程的 AI 整理（flows-recording-recovery.md V8）：只让模型给出名称与说明，步骤结构不变。
 * 优先使用轻量模型，未配置时使用主模型；模型不可用或输出无法解析时返回错误，调用方保留原名称。
 */
package com.example.autop.flow.impl.recording

import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.PolishedName
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.complete
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/** 录制流程名称与说明的 AI 整理。 */
@Singleton
internal class NamePolisher
    @Inject
    constructor(
        private val gateway: LlmGateway,
        private val prompts: PromptRepository,
    ) {
        /** [steps] 为每个步骤的一行描述。 */
        suspend fun polish(
            name: String,
            steps: List<String>,
        ): Outcome<PolishedName> {
            val provider =
                (gateway.forDefault() as? Outcome.Ok)
                    ?.value
                    ?: return Outcome.Err(AppError(code = FlowErrorCodes.LLM_UNAVAILABLE, message = "没有可用的模型"))
            val prompt =
                (prompts.render(TEMPLATE, mapOf("name" to name, "steps" to steps.joinToString("\n"))) as? Outcome.Ok)
                    ?.value
                    ?.text
                    ?: return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "提示词模板缺失：$TEMPLATE"))
            val request =
                LlmRequest(
                    system = listOf(SystemPart(text = "你是手机自动化流程的命名助手。")),
                    messages = listOf(Msg(Role.USER, listOf(Block.Text(prompt)))),
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                )
            val completed =
                when (val r = provider.complete(request)) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return Outcome.Err(r.error)
                }
            val text =
                completed.message.blocks
                    .filterIsInstance<Block.Text>()
                    .joinToString("") { it.text }
            return parse(text)
        }

        @Suppress("SwallowedException") // 解析失败统一返回模型输出错误码，原异常细节不外露
        private fun parse(text: String): Outcome<PolishedName> {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return invalid()
            return try {
                val reply = AutopJson.default.decodeFromString(PolishReply.serializer(), text.substring(start, end + 1))
                if (reply.name.isBlank()) {
                    invalid()
                } else {
                    Outcome.Ok(
                        PolishedName(reply.name.trim(), reply.description.trim()),
                    )
                }
            } catch (e: SerializationException) {
                invalid()
            }
        }

        private fun invalid(): Outcome<PolishedName> =
            Outcome.Err(AppError(code = FlowErrorCodes.LLM_INVALID_OUTPUT, message = "模型输出无法解析"))

        @Serializable
        private data class PolishReply(
            val name: String = "",
            val description: String = "",
        )

        private companion object {
            const val TEMPLATE = "flow.record.polish"
            const val MAX_OUTPUT_TOKENS = 512
        }
    }
