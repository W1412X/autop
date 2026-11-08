/**
 * 任务编辑页界面状态（docs/tech/08 §4.3）：定时触发含单次日期 + 时/分/秒。
 */
@file:Suppress("CyclomaticComplexMethod") // 表单校验逐字段分支

package com.example.autop.feature.tasks.edit

import com.example.autop.feature.tasks.cronTimeOf
import com.example.autop.feature.tasks.isDailyCron
import com.example.autop.feature.tasks.isWorkdayCron
import com.example.autop.task.GroupMode
import com.example.autop.task.LockedBehavior
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.TaskExecutor
import com.example.autop.task.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 编辑页状态。 */
public data class TaskEditUiState(
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    // 基本
    val name: String = "",
    val goal: String = "",
    val needsScreen: Boolean = true,
    // 触发
    val triggerKind: EditTriggerKind = EditTriggerKind.MANUAL,
    val scheduleMode: EditScheduleMode = EditScheduleMode.DAILY,
    /** 单次任务的触发日期（每天/工作日不使用）。 */
    val date: LocalDate = LocalDate.now(),
    val timeHour: Int = DEFAULT_HOUR,
    val timeMinute: Int = 0,
    val timeSecond: Int = 0,
    val cronExpr: String = "",
    /** 事件源候选（id + 可过滤字段 + 所需权限），由 ViewModel 按注册源装配。 */
    val sourceOptions: List<SourceOptionUi> = emptyList(),
    val sourceId: String = "",
    val clauses: List<ClauseUi> = emptyList(),
    val groupAll: Boolean = true,
    val llmCondition: String = "",
    val cooldownMinutes: Int = DEFAULT_COOLDOWN_MINUTES,
    val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    // 执行方式：智能体按目标执行，或运行绑定的流程
    val executorFlow: Boolean = false,
    val flowId: String = "",
    val flowOptions: List<FlowOptionUi> = emptyList(),
    /** 流程参数的输入文本（按参数声明在保存时转成 JSON）。 */
    val flowParams: Map<String, String> = emptyMap(),
    // 执行
    val lockedRemind: Boolean = true,
    val priority: Int = 0,
    // 试运行
    val simEvents: List<String> = emptyList(),
    /** 点过“用最近的事件试一下”（区分“还没查”与“查了没事件”）。 */
    val simQueried: Boolean = false,
    val simPicked: Int? = null,
    val simResult: SimResultUi? = null,
    // 反馈
    val error: String? = null,
    /** 试运行里“直接运行一次”已成功发起。 */
    val simStarted: Boolean = false,
)

/** 可绑定的流程。 */
public data class FlowOptionUi(
    val id: String,
    val name: String,
    val params: List<FlowParamUi>,
)

/** 流程参数的表单描述。 */
public data class FlowParamUi(
    val name: String,
    /** string /
umber / oolean / list / object / 	ime / ny。 */
    val type: String,
    val required: Boolean,
    val defaultText: String,
    val description: String,
    val secret: Boolean,
)

/** 试运行结果。 */
public data class SimResultUi(
    val matched: Boolean,
    val details: List<String>,
)

/** 一个事件源的编辑器展示数据（字段候选与权限提示，id 见 docs/tech/07 §1.4）。 */
public data class SourceOptionUi(
    val id: String,
    val fields: List<String>,
    val requiresPermission: String?,
)

/** 编辑页错误码（文案在 Screen 映射）。 */
public enum class TaskEditError {
    NOT_FOUND,
    NAME,
    GOAL,
    SOURCE,
    CRON,
    TIME,
    COOLDOWN,
    LIMIT,
    FLOW,
    FLOW_PARAM,
}

/** 已有任务 → 表单。 */
internal fun TaskEditUiState.from(task: Task): TaskEditUiState {
    val schedule = task.trigger as? Trigger.Schedule
    val condition = task.trigger as? Trigger.Condition
    val mode = scheduleModeOf(schedule?.spec)
    val time = timeOf(schedule)
    return copy(
        loaded = true,
        isNew = false,
        name = task.name,
        goal = task.goal,
        needsScreen = task.needsScreen,
        executorFlow = task.executor == TaskExecutor.FLOW,
        flowId =
            task.flow
                ?.flowId
                ?.value
                .orEmpty(),
        flowParams =
            task.flow?.params.orEmpty().mapValues { (_, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: v.toString()
            },
        lockedRemind = task.lockedBehavior == LockedBehavior.REMIND,
        priority = task.priority,
        triggerKind = triggerKindOf(task.trigger),
        scheduleMode = mode,
        date = dateOf(schedule),
        timeHour = time.first,
        timeMinute = time.second,
        timeSecond = time.third,
        cronExpr = if (mode == EditScheduleMode.CRON) cronExprOf(schedule).orEmpty() else "",
        sourceId = condition?.sourceId.orEmpty(),
        clauses = clausesOf(condition),
        groupAll = condition?.filter?.mode != GroupMode.ANY,
        llmCondition = condition?.llmCondition.orEmpty(),
        cooldownMinutes = cooldownMinutesOf(condition),
        dailyLimit = condition?.dailyLimit ?: DEFAULT_DAILY_LIMIT,
    )
}

/** 表单校验（[now] 为当前时刻，返回错误码，null = 通过）。 */
internal fun TaskEditUiState.validate(now: Long): String? =
    when {
        name.isBlank() -> TaskEditError.NAME.name
        !executorFlow && goal.isBlank() -> TaskEditError.GOAL.name
        executorFlow && flowId.isBlank() -> TaskEditError.FLOW.name
        executorFlow && missingRequiredParam() -> TaskEditError.FLOW_PARAM.name
        triggerKind == EditTriggerKind.CONDITION && sourceId.isBlank() -> TaskEditError.SOURCE.name
        isBlankCron() -> TaskEditError.CRON.name
        isPastOnce(now) -> TaskEditError.TIME.name
        isInvalidCooldown() -> TaskEditError.COOLDOWN.name
        isInvalidDailyLimit() -> TaskEditError.LIMIT.name
        else -> null
    }

/** 切到单次时：默认时刻已过则顺延到明天（用户显式改过的日期仍由校验把关）。 */
internal fun TaskEditUiState.withUpcomingOnceDate(now: Long): TaskEditUiState =
    if (scheduleMode == EditScheduleMode.ONCE && onceAtMillis() <= now) {
        copy(date = date.plusDays(1))
    } else {
        this
    }

/** 绑定的流程有必填参数既没填也没默认值。 */
private fun TaskEditUiState.missingRequiredParam(): Boolean =
    flowOptions.firstOrNull { it.id == flowId }?.params.orEmpty().any { p ->
        p.required && p.defaultText.isBlank() && flowParams[p.name].isNullOrBlank()
    }

/** cron 自定义模式未填表达式。 */
private fun TaskEditUiState.isBlankCron(): Boolean =
    triggerKind == EditTriggerKind.SCHEDULE && scheduleMode == EditScheduleMode.CRON && cronExpr.isBlank()

/** 单次模式的触发时刻已经过去。 */
private fun TaskEditUiState.isPastOnce(now: Long): Boolean =
    triggerKind == EditTriggerKind.SCHEDULE && scheduleMode == EditScheduleMode.ONCE && onceAtMillis() <= now

/** 条件触发的冷却期低于下限（保存前拦住，避免落到泛化的 task.invalid）。 */
private fun TaskEditUiState.isInvalidCooldown(): Boolean =
    triggerKind == EditTriggerKind.CONDITION && cooldownMinutes < MIN_COOLDOWN_MINUTES

/** 条件触发的每日上限低于下限。 */
private fun TaskEditUiState.isInvalidDailyLimit(): Boolean =
    triggerKind == EditTriggerKind.CONDITION && dailyLimit < MIN_DAILY_LIMIT

private fun triggerKindOf(trigger: Trigger): EditTriggerKind =
    when (trigger) {
        Trigger.Manual -> EditTriggerKind.MANUAL
        is Trigger.Schedule -> EditTriggerKind.SCHEDULE
        is Trigger.Condition -> EditTriggerKind.CONDITION
    }

private fun scheduleModeOf(spec: ScheduleSpec?): EditScheduleMode =
    when (spec) {
        is ScheduleSpec.Once -> EditScheduleMode.ONCE
        is ScheduleSpec.Cron -> cronModeOf(spec.expr)
        null -> EditScheduleMode.DAILY
    }

/** 自定义 cron 与“每天/工作日”的区别：后者是单值时分（秒）+ 固定日/月/周段（见 ScheduleLabels）。 */
private fun cronModeOf(expr: String): EditScheduleMode =
    when {
        isDailyCron(expr) -> EditScheduleMode.DAILY
        isWorkdayCron(expr) -> EditScheduleMode.WORKDAY
        else -> EditScheduleMode.CRON
    }

private fun timeOf(schedule: Trigger.Schedule?): Triple<Int, Int, Int> {
    val once = onceOf(schedule)
    if (once != null) return Triple(once.hour, once.minute, once.second)
    val spec = schedule?.spec as? ScheduleSpec.Cron ?: return Triple(DEFAULT_HOUR, 0, 0)
    return cronTimeOf(spec.expr)
}

private fun dateOf(schedule: Trigger.Schedule?): LocalDate = onceOf(schedule)?.toLocalDate() ?: LocalDate.now()

private fun onceOf(schedule: Trigger.Schedule?): LocalDateTime? =
    (schedule?.spec as? ScheduleSpec.Once)?.let { spec ->
        Instant
            .ofEpochMilli(spec.atMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }

private fun cronExprOf(schedule: Trigger.Schedule?): String? = (schedule?.spec as? ScheduleSpec.Cron)?.expr

private fun clausesOf(condition: Trigger.Condition?): List<ClauseUi> =
    condition?.filter?.clauses.orEmpty().map {
        ClauseUi(field = it.field, op = it.op.name, value = it.value)
    }

private fun cooldownMinutesOf(condition: Trigger.Condition?): Int =
    ((condition?.cooldownMs ?: MINUTE_MS * DEFAULT_COOLDOWN_MINUTES) / MINUTE_MS).toInt()

private const val DEFAULT_HOUR = 8
private const val DEFAULT_COOLDOWN_MINUTES = 10
private const val DEFAULT_DAILY_LIMIT = 20
private const val MINUTE_MS = 60_000L

/** 冷却期下限（分钟，同 TaskValidator 的 60000ms 口径）。 */
internal const val MIN_COOLDOWN_MINUTES: Int = 1

/** 每日上限下限（次，同 TaskValidator 口径）。 */
internal const val MIN_DAILY_LIMIT: Int = 1
