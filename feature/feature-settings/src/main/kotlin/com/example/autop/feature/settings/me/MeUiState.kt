/**
 * "我的"页状态（docs/standards/coding-standards §8：不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.settings.me

import com.example.autop.core.config.UiAppearanceSection
import com.example.autop.core.config.UiBannerSection
import com.example.autop.device.ShellState

/** 横幅提示类型（文案在 feature-settings 的 strings，ViewModel 不接触资源）。 */
public enum class NoticeKind {
    /** 占位分组点击提示（"即将推出"）。 */
    PLACEHOLDER,

    /** 导出成功。 */
    EXPORTED,

    /** 导出失败。 */
    EXPORT_FAILED,

    /** 导入成功。 */
    IMPORTED,

    /** 导入失败。 */
    IMPORT_FAILED,
}

public data class MeUiState(
    /** `ui.appearance` 当前段（配置默认 + 用户覆盖）。 */
    val appearance: UiAppearanceSection = UiAppearanceSection(),
    /** 横幅显示时长（`ui.banner.durationMs`）。 */
    val bannerDurationMs: Long = UiBannerSection().durationMs,
    /** 当前横幅提示；null 表示无。 */
    val notice: NoticeKind? = null,
    /** 主模型显示名（模型分组"模型配置"入口副标题；null=未分配）。 */
    val mainModelName: String? = null,
    /** 向量提供方 id（`llm.embedding.provider`，"检索向量"入口副标题）。 */
    val embeddingProviderId: String = "none",
    /** `ui.overlay.windowType`（悬浮窗通道：auto / accessibility / application，docs/tech/05 §10）。 */
    val overlayWindowType: String = "auto",
    /** `task.host.persistent`（常驻后台，docs/tech/07 §4.5）。 */
    val hostPersistent: Boolean = false,
    /** `task.returnToOriginApp`（任务结束后返回原应用，docs/tech/07 §4.5）。 */
    val returnToOriginApp: Boolean = true,
    /** `task.conditions.enabled`（条件触发总开关，docs/tech/07 §4.2）。 */
    val conditionsEnabled: Boolean = true,
    /** `device.touch.shieldEnabled`（执行期触摸盾总开关，N215）。 */
    val shieldEnabled: Boolean = true,
    /** `task.reminder.snoozeMinutes`（默认稍后分钟数，docs/tech/07 §4.4）。 */
    val snoozeMinutes: Int = 10,
    /** `task.reminder.snoozeOptions`（稍后可选档位）。 */
    val snoozeOptions: List<Int> = listOf(SNOOZE_SHORT, SNOOZE_DEFAULT, SNOOZE_LONG),
    /** `task.reminder.expireMinutes.schedule`（定时任务提醒超时分钟数）。 */
    val reminderExpireSchedule: Int = 60,
    /** `task.reminder.expireMinutes.condition`（条件任务提醒超时分钟数）。 */
    val reminderExpireCondition: Int = 15,
    /** shell 注入通道连接态（「注入通道」入口副标题，N200）。 */
    val shellChannel: ShellState = ShellState.Disconnected,
    /** 无障碍服务已连接（状态卡）。 */
    val accessibilityConnected: Boolean = false,
    /** 通知使用权已授权（系统授权名单判定，与服务连接态无关，N190）。 */
    val notificationListenerGranted: Boolean = false,
    /** AgentHost 前台服务运行中（状态卡）。 */
    val hostRunning: Boolean = false,
    /** 主模型已分配（状态卡）。 */
    val mainModelAssigned: Boolean = false,
)

/** 稍后可选档位（分钟，docs/tech/07 §4.4）。 */
private const val SNOOZE_SHORT = 5
private const val SNOOZE_DEFAULT = 10
private const val SNOOZE_LONG = 30
