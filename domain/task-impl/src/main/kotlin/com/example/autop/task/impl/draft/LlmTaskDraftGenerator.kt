/**
 * 任务草稿生成（docs/tech/07 §6）：LIGHT 模型 + 模板 `task.draft.generate`，
 * 解析失败追加 `task.draft.fix_json` 重试 `task.draft.retries` 次；
 * 结果交给 TaskValidator 校验（不合法返回错误，编辑器标红提示）。
 */
package com.example.autop.task.impl.draft

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.task.Task
import com.example.autop.task.TaskDraftGenerator
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TriggerSource
import com.example.autop.task.core.TaskValidator
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** LLM 任务草稿生成器。 */
@Suppress("LongParameterList") // 草稿生成的端口装配面（gateway/prompts/config/源/ids/clock/json）
public class LlmTaskDraftGenerator(
    private val gateway: LlmGateway,
    private val prompts: PromptRepository,
    private val configProvider: ConfigProvider,
    private val sources: () -> List<TriggerSource>,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val logger: Logger,
    private val json: Json = AutopJson.default,
) : TaskDraftGenerator {
    @Suppress("ReturnCount") // 渲染/LLM/解析逐级早退是生成链的自然结构
    override suspend fun draft(description: String): Outcome<Task> {
        val nowMillis = clock.nowMillis()
        val zone = ZoneId.systemDefault()
        val userText = renderGenerate(description, nowMillis, zone) ?: return Outcome.Err(promptError())
        var messages = listOf(Msg(Role.USER, listOf(Block.Text(userText))))
        val retries = configProvider.current()[TaskConfig.DRAFT].retries
        repeat(1 + retries) { attempt ->
            val text = LlmCalls.completeText(gateway, messages)
            val raw = (text as? Outcome.Ok)?.value ?: return Outcome.Err((text as Outcome.Err).error)
            val parsed = parseDraft(raw)
            if (parsed != null) return buildTask(parsed, nowMillis)
            messages = fixupMessages(messages, raw, attempt)
        }
        return Outcome.Err(
            com.example.autop.core.common.AppError(
                code = TaskErrorCodes.INVALID,
                message = "draft json parse failed after ${1 + retries} attempts",
            ),
        )
    }

    private fun renderGenerate(
        description: String,
        nowMillis: Long,
        zone: java.time.ZoneId,
    ): String? {
        val rendered =
            prompts.render(
                PROMPT_GENERATE,
                mapOf(
                    VAR_DESCRIPTION to description,
                    VAR_SOURCES to renderSources(),
                    VAR_NOW to Instant.ofEpochMilli(nowMillis).atZone(zone).format(TIME_FORMAT),
                    VAR_TIMEZONE to zone.id,
                ),
            )
        return (rendered as? Outcome.Ok)?.value?.text
    }

    private fun promptError(): com.example.autop.core.common.AppError =
        com.example.autop.core.common
            .AppError(code = TaskErrorCodes.INVALID, message = "prompt render failed")

    /** 解析草稿 JSON（失败返回 null 进入重试）。 */
    private fun parseDraft(raw: String): TaskDraftDto? {
        val body = extractJsonObject(raw) ?: return null
        return try {
            json.decodeFromString(TaskDraftDto.serializer(), body)
        } catch (_: SerializationException) {
            logger.w(TAG, "draft_output_unparsable", "raw" to raw.take(LOG_TEXT_CHARS))
            null
        } catch (_: IllegalArgumentException) {
            logger.w(TAG, "draft_output_unparsable", "raw" to raw.take(LOG_TEXT_CHARS))
            null
        }
    }

    private fun fixupMessages(
        messages: List<Msg>,
        raw: String,
        attempt: Int,
    ): List<Msg> {
        val fix =
            prompts.render(
                PROMPT_FIX_JSON,
                mapOf(VAR_ERROR to "第 ${attempt + 1} 次输出无法解析"),
            )
        val fixText = (fix as? Outcome.Ok)?.value?.text ?: "请只输出修正后的 JSON。"
        return messages +
            Msg(Role.ASSISTANT, listOf(Block.Text(raw))) +
            Msg(Role.USER, listOf(Block.Text(fixText)))
    }

    /** 校验并组装 Task（不合法返回 `task.invalid`）。 */
    private fun buildTask(
        dto: TaskDraftDto,
        nowMillis: Long,
    ): Outcome<Task> {
        val task = dto.toTask(TaskId(ids.newId()), nowMillis)
        val limits = configProvider.current()[TaskConfig.LIMITS]
        val problems =
            TaskValidator.validate(
                task = task,
                knownSources = sources().map { it.id }.toSet(),
                minCooldownMs = limits.minCooldownMs,
                maxDailyLimit = limits.maxDailyLimit,
            )
        return if (problems.isValid) {
            Outcome.Ok(task)
        } else {
            Outcome.Err(
                com.example.autop.core.common.AppError(
                    code = TaskErrorCodes.INVALID,
                    message = "draft invalid: ${problems.byField.keys}",
                ),
            )
        }
    }

    private fun renderSources(): String =
        sources().joinToString("\n") { source ->
            "- ${source.id}：${source.fieldNames.joinToString("、")}"
        }

    private companion object {
        const val PROMPT_GENERATE = "task.draft.generate"
        const val PROMPT_FIX_JSON = "task.draft.fix_json"
        const val VAR_DESCRIPTION = "description"
        const val VAR_SOURCES = "sources"
        const val VAR_NOW = "now"
        const val VAR_TIMEZONE = "timezone"
        const val VAR_ERROR = "error"
        const val TAG = "LlmTaskDraftGenerator"
        const val LOG_TEXT_CHARS = 200
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
