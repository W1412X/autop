/**
 * LLM 条件判定（docs/tech/07 §4.2/§6）：LIGHT 模型 + 模板 `task.condition.judge`，
 * 只用于 llmCondition；事件内容按不可信数据传入模板。
 */
package com.example.autop.task.impl.draft

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.task.ConditionEvaluator
import com.example.autop.task.ConditionResult
import com.example.autop.task.TriggerEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** LLM 条件判定器（id 与 `task.conditions.evaluator` 默认值一致）。 */
public class LlmConditionEvaluator(
    private val gateway: LlmGateway,
    private val prompts: PromptRepository,
    private val logger: Logger,
    private val json: Json = AutopJson.default,
) : ConditionEvaluator {
    override val id: String = EVALUATOR_ID

    @Suppress("ReturnCount") // 渲染/LLM/解析逐级早退是判定链的自然结构
    override suspend fun evaluate(
        condition: String,
        event: TriggerEvent,
    ): Outcome<ConditionResult> {
        val rendered =
            prompts.render(
                PROMPT_JUDGE,
                mapOf(
                    VAR_CONDITION to condition,
                    VAR_EVENT to json.encodeToString(TriggerEvent.serializer(), event),
                ),
            )
        val userText =
            when (rendered) {
                is Outcome.Ok -> rendered.value.text
                is Outcome.Err -> return Outcome.Err(rendered.error)
            }
        val text =
            when (val result = LlmCalls.completeText(gateway, listOf(Msg(Role.USER, listOf(Block.Text(userText)))))) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return Outcome.Err(result.error)
            }
        val parsed = parseJudge(text) ?: return parseError()
        return Outcome.Ok(
            ConditionResult(
                match = parsed.match,
                reason = parsed.reason,
                extracted = parsed.extracted,
            ),
        )
    }

    private fun parseJudge(text: String): JudgeDto? {
        val body = extractJsonObject(text) ?: return null
        return try {
            json.decodeFromString(JudgeDto.serializer(), body)
        } catch (_: SerializationException) {
            logger.w(TAG, "judge_output_unparsable", "text" to text.take(LOG_TEXT_CHARS))
            null
        } catch (_: IllegalArgumentException) {
            logger.w(TAG, "judge_output_unparsable", "text" to text.take(LOG_TEXT_CHARS))
            null
        }
    }

    private fun parseError(): Outcome.Err =
        Outcome.Err(
            com.example.autop.core.common.AppError(
                code = "task.condition_unparsable",
                message = "judge output is not valid json",
            ),
        )

    /** `task.condition.judge` 的输出结构。 */
    @Serializable
    private data class JudgeDto(
        val match: Boolean,
        val reason: String? = null,
        val extracted: Map<String, String> = emptyMap(),
    )

    public companion object {
        /** 与 TaskConfig `task.conditions.evaluator` 默认值一致。 */
        public const val EVALUATOR_ID: String = "evaluator.llm"
        private const val PROMPT_JUDGE = "task.condition.judge"
        private const val VAR_CONDITION = "condition"
        private const val VAR_EVENT = "event"
        private const val TAG = "LlmConditionEvaluator"
        private const val LOG_TEXT_CHARS = 200
    }
}
