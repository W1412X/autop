/**
 * "我的"页状态（docs/standards/coding-standards §8：不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.settings.me

import com.example.autop.core.config.DeviceActionSection
import com.example.autop.core.config.UiAppearanceSection
import com.example.autop.core.config.UiBannerSection
import com.example.autop.device.ShellState
import com.example.autop.device.UpdateState

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
    val defaultModelName: String? = null,
    /** 向量提供方 id（`llm.embedding.provider`，"检索向量"入口副标题）。 */
    val embeddingProviderId: String = "none",
    /** `ui.overlay.windowType`（悬浮窗通道：auto / accessibility / application，docs/tech/05 §10）。 */
    val overlayWindowType: String = "auto",
    /** `ui.overlay.enabled`（悬浮球是否显示）。 */
    val overlayEnabled: Boolean = true,
    /** `ui.overlay.displayMode`（悬浮球显示方式：auto / always / edge）。 */
    val overlayDisplayMode: String = "auto",
    /** `ui.overlay.opacity`（球体与贴边小条透明度，1.0=完全不透明）。 */
    val overlayOpacity: Double = 1.0,
    /** `task.host.persistent`（常驻后台，docs/tech/07 §4.5）。 */
    val hostPersistent: Boolean = false,
    /** `task.returnToOriginApp`（任务结束后返回原应用，docs/tech/07 §4.5）。 */
    val returnToOriginApp: Boolean = true,
    /** `task.conditions.enabled`（条件触发总开关，docs/tech/07 §4.2）。 */
    val conditionsEnabled: Boolean = true,
    /** `features.flows`（流程 Beta 开关，docs/adr/0030-beta-feature-switch.md）。 */
    val flowsEnabled: Boolean = false,
    /** `device.touch.shieldEnabled`（执行期触摸盾总开关，N215）。 */
    val shieldEnabled: Boolean = true,
    /** `device.action.delayMs`（动作后固定等待毫秒数，tool_optimize 03 §5.1）。 */
    val actionDelayMs: Long = DeviceActionSection().delayMs,
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
    /** 默认模型已设置（状态卡）。 */
    val defaultModelAssigned: Boolean = false,
    /** 应用更新状态（关于分组"检查更新"与新版本横幅）。 */
    val update: UpdateState = UpdateState.Idle,
    /** 手动检测结果提示（已是最新/检测失败；null 表示无）。 */
    val updateNotice: UpdateNotice? = null,
    /** `ui.debug.verboseLogging`（调试模式）：true 时显示"工具测试记录"入口（tool_optimize D20）。 */
    val probeEntryVisible: Boolean = false,
    /** `device.intent.preferredMap`（导航应用偏好：auto / ask / 应用 id）。 */
    val navigationPreferred: String = "auto",
    /** 导航规则表里的全部应用及其安装状态（设置行副标题与选项弹层用）。 */
    val navigationApps: List<NavigationOption> = emptyList(),
)

/** 导航应用选项：规则表中的一个应用及其是否已安装。 */
public data class NavigationOption(
    val id: String,
    val label: String,
    val installed: Boolean,
)

/** 手动检查更新的结果提示。 */
public enum class UpdateNotice {
    /** 已是最新版本。 */
    UP_TO_DATE,

    /** 检测失败（网络不可达等）。 */
    CHECK_FAILED,

    /** 下载失败。 */
    DOWNLOAD_FAILED,

    /** 需先授权"安装未知应用"（已跳系统授权页）。 */
    INSTALL_PERMISSION,
}

/** 稍后可选档位（分钟，docs/tech/07 §4.4）。 */
private const val SNOOZE_SHORT = 5
private const val SNOOZE_DEFAULT = 10
private const val SNOOZE_LONG = 30
