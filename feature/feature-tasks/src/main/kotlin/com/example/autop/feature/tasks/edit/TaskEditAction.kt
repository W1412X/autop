/**
 * 任务编辑页用户意图（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.edit

import java.time.LocalDate

/** 编辑页意图。 */
public sealed interface TaskEditAction {
    /** 一句话描述（草稿生成输入）。 */
    public data class SetDescription(
        val value: String,
    ) : TaskEditAction

    /** 生成草稿。 */
    public data object GenerateDraft : TaskEditAction

    /** 名称。 */
    public data class SetName(
        val value: String,
    ) : TaskEditAction

    /** 目标。 */
    public data class SetGoal(
        val value: String,
    ) : TaskEditAction

    /** 执行方式：false = 智能体，true = 流程。 */
    public data class SetExecutorFlow(
        val flow: Boolean,
    ) : TaskEditAction

    /** 选择绑定的流程。 */
    public data class SetFlow(
        val flowId: String,
    ) : TaskEditAction

    /** 填写流程参数。 */
    public data class SetFlowParam(
        val name: String,
        val value: String,
    ) : TaskEditAction

    /** 需要操作屏幕。 */
    public data class SetNeedsScreen(
        val value: Boolean,
    ) : TaskEditAction

    /** 触发方式。 */
    public data class SetTriggerKind(
        val kind: EditTriggerKind,
    ) : TaskEditAction

    /** 定时模式。 */
    public data class SetScheduleMode(
        val mode: EditScheduleMode,
    ) : TaskEditAction

    /** 单次任务的触发日期。 */
    public data class SetDate(
        val date: LocalDate,
    ) : TaskEditAction

    /** 定时的时/分/秒。 */
    public data class SetTime(
        val hour: Int,
        val minute: Int,
        val second: Int,
    ) : TaskEditAction

    /** 自定义 cron。 */
    public data class SetCron(
        val value: String,
    ) : TaskEditAction

    /** 条件事件源。 */
    public data class SetSource(
        val sourceId: String,
    ) : TaskEditAction

    /** 过滤条件编辑。 */
    public data class SetClause(
        val index: Int,
        val clause: ClauseUi,
    ) : TaskEditAction

    /** 新增过滤条件。 */
    public data object AddClause : TaskEditAction

    /** 删除过滤条件。 */
    public data class RemoveClause(
        val index: Int,
    ) : TaskEditAction

    /** ALL/ANY 切换。 */
    public data class SetGroupAll(
        val value: Boolean,
    ) : TaskEditAction

    /** LLM 条件。 */
    public data class SetLlmCondition(
        val value: String,
    ) : TaskEditAction

    /** 冷却期（分钟）。 */
    public data class SetCooldown(
        val minutes: Int,
    ) : TaskEditAction

    /** 每日上限。 */
    public data class SetDailyLimit(
        val value: Int,
    ) : TaskEditAction

    /** 锁屏行为。 */
    public data class SetLockedBehavior(
        val remind: Boolean,
    ) : TaskEditAction

    /** 优先级。 */
    public data class SetPriority(
        val value: Int,
    ) : TaskEditAction

    /** 打开试运行（拉最近事件）。 */
    public data object OpenSimulate : TaskEditAction

    /** 选用一个最近事件。 */
    public data class PickSimEvent(
        val index: Int,
    ) : TaskEditAction

    /** 用选中事件实际运行一次。 */
    public data object RunSimOnce : TaskEditAction

    /** 保存。 */
    public data object Save : TaskEditAction

    /** 返回。 */
    public data object Back : TaskEditAction
}

/** 触发方式（编辑态）。 */
public enum class EditTriggerKind {
    MANUAL,
    SCHEDULE,
    CONDITION,
}

/** 定时模式（编辑态）。 */
public enum class EditScheduleMode {
    ONCE,
    DAILY,
    WORKDAY,
    CRON,
}

/** 一条过滤条件（编辑态）。 */
public data class ClauseUi(
    val field: String,
    val op: String,
    val value: String,
)
