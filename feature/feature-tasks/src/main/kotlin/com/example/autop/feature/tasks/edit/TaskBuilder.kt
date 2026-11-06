/**
 * 编辑表单 → [Task] 装配（保存与试运行共用，docs/tech/07 §1.1 结构）。
 */
package com.example.autop.feature.tasks.edit

import com.example.autop.core.common.FlowId
import com.example.autop.core.common.TaskId
import com.example.autop.task.FilterClause
import com.example.autop.task.FilterGroup
import com.example.autop.task.FilterOp
import com.example.autop.task.FlowBinding
import com.example.autop.task.GroupMode
import com.example.autop.task.LockedBehavior
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.TaskExecutor
import com.example.autop.task.Trigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId

/** 当前表单 → [Task]（[existingId] 为 null 时用 [newId] 建新任务）。 */
internal fun TaskEditUiState.buildTask(
    existingId: TaskId?,
    newId: String,
    now: Long,
): Task =
    Task(
        id = existingId ?: TaskId(newId),
        name = name,
        goal = if (executorFlow) goal.ifBlank { flowOptions.firstOrNull { it.id == flowId }?.name.orEmpty() } else goal,
        executor = if (executorFlow) TaskExecutor.FLOW else TaskExecutor.AGENT,
        flow = if (executorFlow) FlowBinding(FlowId(flowId), flowParamsJson()) else null,
        trigger = buildTrigger(),
        needsScreen = needsScreen,
        lockedBehavior = if (lockedRemind) LockedBehavior.REMIND else LockedBehavior.SKIP,
        priority = priority,
        createdAt = now,
        updatedAt = now,
    )

/** 流程参数文本 → 按声明类型转 JSON；空值不写入（运行时用默认值）。 */
internal fun TaskEditUiState.flowParamsJson(): JsonObject {
    val declared =
        flowOptions
            .firstOrNull { it.id == flowId }
            ?.params
            .orEmpty()
            .associateBy { it.name }
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, text) in flowParams) {
        if (text.isBlank()) continue
        out[key] = paramJson(declared[key]?.type, text)
    }
    return JsonObject(out)
}

private fun paramJson(
    type: String?,
    text: String,
): JsonElement =
    when (type) {
        "number", "time" -> text.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
        "boolean" -> JsonPrimitive(text.trim().lowercase() in TRUE_WORDS)
        "list", "object", "any" -> runCatching { Json.parseToJsonElement(text) }.getOrDefault(JsonPrimitive(text))
        else -> JsonPrimitive(text)
    }

private val TRUE_WORDS = setOf("true", "是", "1")

/** 当前表单的触发方式。 */
internal fun TaskEditUiState.buildTrigger(): Trigger =
    when (triggerKind) {
        EditTriggerKind.MANUAL -> Trigger.Manual
        EditTriggerKind.SCHEDULE -> Trigger.Schedule(scheduleSpec())
        EditTriggerKind.CONDITION -> conditionSpec()
    }

private fun TaskEditUiState.scheduleSpec(): ScheduleSpec =
    when (scheduleMode) {
        EditScheduleMode.ONCE -> ScheduleSpec.Once(atMillis = onceAtMillis())
        EditScheduleMode.DAILY -> ScheduleSpec.Cron(plainCron(ANY_WEEK), zoneId())
        EditScheduleMode.WORKDAY -> ScheduleSpec.Cron(plainCron(WORKDAY_WEEK), zoneId())
        EditScheduleMode.CRON -> ScheduleSpec.Cron(cronExpr, zoneId())
    }

/** 单次触发时刻：所选日期 + 时:分:秒。 */
internal fun TaskEditUiState.onceAtMillis(): Long =
    date
        .atTime(timeHour, timeMinute, timeSecond)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

/** 每天/工作日的 cron：秒为 0 写 5 段（兼容旧数据），否则 6 段带秒。 */
private fun TaskEditUiState.plainCron(week: String): String =
    if (timeSecond == 0) {
        "$timeMinute $timeHour * * $week"
    } else {
        "$timeSecond $timeMinute $timeHour * * $week"
    }

private fun TaskEditUiState.conditionSpec(): Trigger.Condition =
    Trigger.Condition(
        sourceId = sourceId,
        filter =
            FilterGroup(
                mode = if (groupAll) GroupMode.ALL else GroupMode.ANY,
                clauses = clauses.filter { it.field.isNotBlank() }.map { it.toFilter() },
            ),
        llmCondition = llmCondition.takeIf { it.isNotBlank() },
        cooldownMs = cooldownMinutes * MINUTE_MS,
        dailyLimit = dailyLimit,
    )

private fun ClauseUi.toFilter(): FilterClause =
    FilterClause(
        field = field,
        op = runCatching { FilterOp.valueOf(op) }.getOrDefault(FilterOp.CONTAINS),
        value = value,
    )

private fun zoneId(): String = ZoneId.systemDefault().id

private const val MINUTE_MS = 60_000L

/** 每天的 cron 周段。 */
private const val ANY_WEEK = "*"

/** 工作日的 cron 周段。 */
private const val WORKDAY_WEEK = "1-5"
