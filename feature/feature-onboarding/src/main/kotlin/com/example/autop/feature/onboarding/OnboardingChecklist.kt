/**
 * 引导与健康检查清单状态（docs/tech/08 §4.7：卡片顺序固定，必需项未完成不能进入主界面；
 * docs/standards/coding-standards §8：不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.onboarding

/** 检查项 id（卡片顺序见 [OnboardingUiState.items] 构造顺序，docs/tech/08 §4.7）。 */
public enum class CheckId {
    /** 模型配置（必需）。 */
    MODEL,

    /** 无障碍服务（必需）。 */
    ACCESSIBILITY,

    /** 悬浮窗权限（推荐）。 */
    OVERLAY,

    /** 通知权限。 */
    NOTIFICATIONS,

    /** 通知使用权。 */
    NOTIFICATION_LISTENER,

    /** 全屏提醒权限（仅 Capability.FULL_SCREEN_INTENT_NEEDS_GRANT 时显示）。 */
    FULL_SCREEN_INTENT,

    /** 精确闹钟（仅 Capability.EXACT_ALARM_NEEDS_GRANT 时显示；未授权时定时任务降级为不准时）。 */
    EXACT_ALARM,

    /** 电池优化白名单。 */
    BATTERY_OPTIMIZATIONS,

    /** 厂商后台设置（仅 DeviceQuirks 有 `autostartIntent` 时显示）。 */
    VENDOR_BACKGROUND,
}

/** 一个检查项的当前状态。 */
public data class CheckItem(
    val id: CheckId,
    /** 是否已完成。 */
    val done: Boolean,
    /** 是否为必需项（必需项未完成时"开始使用"禁用）。 */
    val required: Boolean,
    /** 是否显示（能力/机型闸门不满足时整卡隐藏）。 */
    val visible: Boolean,
)

/** 引导与健康检查页状态。 */
public data class OnboardingUiState(
    /** 检查项（顺序即 docs/tech/08 §4.7 的卡片顺序）。 */
    val items: List<CheckItem> = emptyList(),
    /** "允许受限设置"图文说明（Capability.RESTRICTED_SETTINGS 时在无障碍卡片下显示）。 */
    val restrictedSettings: Boolean = false,
    /** 厂商后台设置跳转描述（DeviceQuirks `autostartIntent`，`component:pkg/cls`；null=无此卡片）。 */
    val vendorComponentSpec: String? = null,
    /** "开始使用"已点击并写入 `ui.onboarding.completed`（路由层据此收尾进入主界面）。 */
    val completed: Boolean = false,
) {
    /** 必需项是否全部完成。 */
    public val requiredDone: Boolean
        get() = items.none { it.required && !it.done }
}
