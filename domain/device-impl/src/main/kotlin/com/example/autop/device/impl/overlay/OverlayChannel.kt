/**
 * 悬浮窗通道选择（docs/tech/05 §10、ADR-0004）：双通道按配置与能力查询结果切换，
 * 核心代码不按 ROM 分支（厂商差异走 `device_quirks.json`）。
 */
package com.example.autop.device.impl.overlay

/** 悬浮窗通道（ADR-0004）。 */
public enum class OverlayChannel {
    /** `TYPE_ACCESSIBILITY_OVERLAY`：无障碍服务上下文，免悬浮窗权限，主通道。 */
    ACCESSIBILITY,

    /** `TYPE_APPLICATION_OVERLAY`：应用上下文，需悬浮窗权限，备用通道。 */
    APPLICATION,
}

/** 通道选择的纯规则（配置 + quirks + 能力查询结果），无 Android 依赖，便于单测。 */
public object OverlayChannelSelector {
    /** `ui.overlay.windowType` 的自动模式值。 */
    public const val AUTO: String = "auto"

    /** `ui.overlay.windowType` 的无障碍窗口模式值。 */
    public const val TYPE_ACCESSIBILITY: String = "accessibility"

    /** `ui.overlay.windowType` 的普通悬浮窗模式值。 */
    public const val TYPE_APPLICATION: String = "application"

    /**
     * 选择当前通道（docs/tech/05 §10）：[configuredType] 为 `ui.overlay.windowType`；
     * `auto` 时优先采用 quirks 的 `overlayWindowType`（[quirkType]），否则无障碍优先、
     * 权限兜底；显式配置只走该通道，前提不满足时不显示悬浮窗（返回 null）。
     */
    public fun select(
        configuredType: String,
        quirkType: String?,
        accessibilityConnected: Boolean,
        overlayPermissionGranted: Boolean,
    ): OverlayChannel? {
        val effective = if (configuredType == AUTO) quirkType ?: AUTO else configuredType
        return when (effective) {
            TYPE_ACCESSIBILITY -> OverlayChannel.ACCESSIBILITY.takeIf { accessibilityConnected }
            TYPE_APPLICATION -> OverlayChannel.APPLICATION.takeIf { overlayPermissionGranted }
            else ->
                when {
                    accessibilityConnected -> OverlayChannel.ACCESSIBILITY
                    overlayPermissionGranted -> OverlayChannel.APPLICATION
                    else -> null
                }
        }
    }
}
