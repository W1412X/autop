/**
 * 对话列表页状态（docs/tech/08 §4.1，docs/standards/coding-standards §8：
 * 不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.chat.list

import com.example.autop.agent.RunOutcome

/** 相对时间桶（文案在 strings，ViewModel 不接触资源）。 */
public enum class AgeUnit {
    JUST_NOW,
    MINUTES,
    HOURS,
    DAYS,
}

/** 列表行的相对时间（[AgeUnit.JUST_NOW] 时 [count] 无意义）。 */
public data class RelativeAge(
    val unit: AgeUnit,
    val count: Int,
)

/** 对话列表行（docs/tech/08 §4.1 PagedList 行）。 */
public data class ChatThreadRow(
    val threadId: String,
    /** 标题（线程标题为空时取预览，仍为空由 Screen 显示"未命名对话"）。 */
    val title: String?,
    val preview: String?,
    val status: RunOutcome?,
    /** 最后更新时间（Unix epoch 毫秒；相对时间由 Screen 渲染时计算）。 */
    val updatedAt: Long,
)

/** [updatedAt] 相对 [now] 的时间桶（纯函数，Screen 映射到 strings）。 */
public fun ageOf(
    updatedAt: Long,
    now: Long,
): RelativeAge {
    val delta = (now - updatedAt).coerceAtLeast(0)
    val minutes = delta / MINUTES_MS
    return when {
        minutes < 1 -> RelativeAge(AgeUnit.JUST_NOW, 0)
        minutes < MINUTES_PER_HOUR -> RelativeAge(AgeUnit.MINUTES, minutes.toInt())
        minutes < MINUTES_PER_DAY -> RelativeAge(AgeUnit.HOURS, (minutes / MINUTES_PER_HOUR).toInt())
        else -> RelativeAge(AgeUnit.DAYS, (minutes / MINUTES_PER_DAY).toInt())
    }
}

private const val MINUTES_MS: Long = 60_000
private const val HOURS_PER_DAY: Long = 24
private const val MINUTES_PER_HOUR: Long = 60
private const val MINUTES_PER_DAY: Long = MINUTES_PER_HOUR * HOURS_PER_DAY

/** 运行中卡片（docs/tech/08 §4.1 ActiveRunCard）。 */
public data class ActiveRunCardModel(
    val runId: String,
    /** 对话 Run 的名称（线程标题；空则由 Screen 显示默认文案）。 */
    val title: String?,
    val currentStep: String?,
    val stepCount: Int,
    /** 步数预算（`agent.budget.maxSteps`，≤0=不限）；进度条 = stepCount / budget（不限时不定进度）。 */
    val budget: Int,
)

/** 列表页弹层状态（null 表示无）。 */
public data class ChatListSheet(
    /** 长按行菜单的目标线程。 */
    val menuThreadId: String? = null,
    /** 重命名输入框的目标线程。 */
    val renameThreadId: String? = null,
    /** 删除确认框的目标线程。 */
    val deleteThreadId: String? = null,
)

/** 分页状态（文案由 Screen 映射到 strings，转 [com.example.autop.core.ui.component.PagedListState]）。 */
public data class ChatPaging(
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val failed: Boolean = false,
)

/** 对话列表页状态。 */
public data class ChatListUiState(
    /** 无障碍服务是否已连接（false 时新建禁用、显示提示，docs/tech/08 §4.1）。 */
    val accessibilityConnected: Boolean = true,
    /** SDK ≥ 33 时"去开启"前需先弹受限设置说明（Capability.RESTRICTED_SETTINGS）。 */
    val restrictedSettings: Boolean = false,
    val threads: List<ChatThreadRow> = emptyList(),
    val paging: ChatPaging = ChatPaging(),
    /** 运行中的 Run（无则不显示卡片）。 */
    val active: ActiveRunCardModel? = null,
    val sheet: ChatListSheet = ChatListSheet(),
)
