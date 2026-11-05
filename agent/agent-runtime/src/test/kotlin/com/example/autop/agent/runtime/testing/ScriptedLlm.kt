/**
 * 脚本化 LLM 测试基建（docs/tech/04 §6）：[ScriptedLlmProvider] 按序吐出预设应答并
 * 记录全部请求（供断言上下文内容）；[FakeLlmGateway] 对任意角色返回同一 provider。
 */
package com.example.autop.agent.runtime.testing

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelCapabilities
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelRole
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一轮预设应答。 */
internal sealed interface ScriptedTurn {
    /** 依次发出的流事件（不补 Completed，由 [ScriptedLlmProvider] 收尾时要求完整）。 */
    data class Events(
        val events: List<LlmStreamEvent>,
    ) : ScriptedTurn

    /** 以 [LlmException] 失败（如 `llm.context_exceeded`）。 */
    data class Failure(
        val error: AppError,
    ) : ScriptedTurn
}

/** 脚本化 provider：脚本耗尽后重复最后一轮。 */
internal class ScriptedLlmProvider(
    override val profile: ModelProfile = TEST_PROFILE,
) : LlmProvider {
    /** 收到的全部请求（供断言 system/messages/tools）。 */
    val requests: MutableList<LlmRequest> = mutableListOf()

    private val turns = ArrayDeque<ScriptedTurn>()

    /** 追加一轮纯文本应答。 */
    fun say(text: String): ScriptedLlmProvider = enqueueTurn(completed(text = text))

    /** 追加一轮工具调用应答（可多个调用，stopReason=TOOL_USE）。 */
    fun call(
        vararg calls: Pair<String, String>,
        text: String? = null,
    ): ScriptedLlmProvider {
        val blocks =
            listOfNotNull(text?.let { Block.Text(it) }) +
                calls.mapIndexed { i, (name, args) ->
                    Block.ToolCall(id = "c${requests.size}-$i", name = name, argsJson = args)
                }
        return enqueueTurn(completed(blocks = blocks, stopReason = StopReason.TOOL_USE))
    }

    /** 追加一轮以 [LlmException] 失败的应答。 */
    fun fail(error: AppError): ScriptedLlmProvider = apply { turns += ScriptedTurn.Failure(error) }

    /** 追加一轮自定义应答。 */
    fun enqueueTurn(completed: LlmStreamEvent.Completed): ScriptedLlmProvider =
        apply {
            val events =
                completed.message.blocks.map { block ->
                    when (block) {
                        is Block.Text -> LlmStreamEvent.TextDelta(block.text)
                        else -> LlmStreamEvent.ToolCallStarted(0, "", (block as? Block.ToolCall)?.name ?: "")
                    }
                } + completed
            turns += ScriptedTurn.Events(events)
        }

    /** 追加任意事件序列（无 Completed 的流会以 BAD_RESPONSE 结束，用于异常路径）。 */
    fun enqueueRaw(vararg events: LlmStreamEvent): ScriptedLlmProvider =
        apply { turns += ScriptedTurn.Events(events.toList()) }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            requests += request
            val turn =
                turns.removeFirstOrNull()
                    ?: throw LlmException(
                        AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "no scripted turn left"),
                    )
            when (turn) {
                is ScriptedTurn.Events -> turn.events.forEach { emit(it) }
                is ScriptedTurn.Failure -> throw LlmException(turn.error)
            }
        }

    private fun completed(
        blocks: List<Block> = emptyList(),
        text: String? = null,
        stopReason: StopReason = StopReason.END_TURN,
    ): LlmStreamEvent.Completed =
        LlmStreamEvent.Completed(
            message = Msg(Role.ASSISTANT, listOfNotNull(text?.let { Block.Text(it) }) + blocks),
            stopReason = stopReason,
            usage = Usage(inputTokens = 10, outputTokens = 10),
        )

    companion object {
        val TEST_PROFILE: ModelProfile =
            ModelProfile(
                id = "test-profile",
                displayName = "测试模型",
                protocol = "openai",
                baseUrl = "http://localhost",
                apiKeyRef = "test-key",
                model = "test-model",
                contextWindow = 200000,
                maxOutputTokens = 8192,
                capabilities = ModelCapabilities(tools = true, vision = true, thinking = false),
            )
    }
}

/** 对任意角色/profile 都返回同一 provider 的网关。 */
internal class FakeLlmGateway(
    val provider: LlmProvider,
) : LlmGateway {
    override fun forRole(role: ModelRole): Outcome<LlmProvider> = Outcome.Ok(provider)

    override fun forProfile(profileId: String): Outcome<LlmProvider> = Outcome.Ok(provider)
}
