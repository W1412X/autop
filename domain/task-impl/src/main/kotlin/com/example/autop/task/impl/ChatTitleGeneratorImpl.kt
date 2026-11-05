/**
 * 对话标题生成（docs/tech/08 §4.2）：首条用户消息 → 模板 `chat.title.generate` + LIGHT 模型，
 * 开关 `ui.chat.autoTitle`；任何失败降级为 null（保留占位标题），不阻塞对话主链路。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.impl.draft.LlmCalls
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** LLM 对话标题生成器。 */
public class ChatTitleGeneratorImpl(
    private val gateway: LlmGateway,
    private val prompts: PromptRepository,
    private val configProvider: ConfigProvider,
    private val logger: Logger,
) : ChatTitleGenerator {
    @Suppress("TooGenericExceptionCaught") // 可选增强：任何失败降级为不改标题
    override suspend fun generate(firstMessage: String): String? {
        if (!configProvider.current()[UiConfig.CHAT].autoTitle) return null
        return try {
            refine(firstMessage)
        } catch (e: TimeoutCancellationException) {
            logger.w(TAG, "chat_title_timeout", "timeoutMs" to TIMEOUT_MS, "error" to (e.message ?: "timeout"))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "chat_title_failed", "error" to (e.message ?: "unknown"))
            null
        }
    }

    @Suppress("ReturnCount") // 渲染/补全/清洗逐级早退是生成链的自然结构
    private suspend fun refine(firstMessage: String): String? {
        val rendered =
            prompts.render(
                PROMPT_ID,
                mapOf(VAR_MESSAGE to firstMessage.take(PROMPT_MESSAGE_CHARS)),
            )
        val userText = (rendered as? Outcome.Ok)?.value?.text ?: return null
        val messages = listOf(Msg(Role.USER, listOf(Block.Text(userText))))
        val raw = withTimeout(TIMEOUT_MS) { LlmCalls.completeText(gateway, messages) }
        val text = (raw as? Outcome.Ok)?.value ?: return null
        return text
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.take(MAX_CHARS)
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "ChatTitle"
        const val PROMPT_ID = "chat.title.generate"
        const val VAR_MESSAGE = "message"
        const val PROMPT_MESSAGE_CHARS = 400
        const val TIMEOUT_MS = 10_000L
        const val MAX_CHARS = 30
    }
}
