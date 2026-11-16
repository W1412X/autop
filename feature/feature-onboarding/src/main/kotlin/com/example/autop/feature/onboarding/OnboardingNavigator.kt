/**
 * feature-onboarding 的导航回调（docs/tech/08 §3：feature 暴露
 * `NavGraphBuilder.onboardingGraph(navigator: OnboardingNavigator)`，
 * 系统设置跳转与路由收尾由 app 侧实现）。
 */
package com.example.autop.feature.onboarding

/** 引导与健康检查页的导航回调。 */
public interface OnboardingNavigator {
    /** 应用内设置子页（[openAppPage] 的目标）。 */
    public enum class AppPage {
        /** 模型配置（`me/models`）。 */
        MODEL,

        /** 注入通道（`me/shell`，无线调试配对引导）。 */
        SHELL,
    }

    /** 打开应用内设置子页（app 按 [AppPage] 路由）。 */
    public fun openAppPage(page: AppPage)

    /** 系统无障碍设置。 */
    public fun openAccessibilitySettings()

    /** 系统悬浮窗权限设置。 */
    public fun openOverlaySettings()

    /** 本应用通知权限设置。 */
    public fun openNotificationSettings()

    /** 系统通知使用权（通知读取）设置。 */
    public fun openNotificationListenerSettings()

    /** 系统全屏提醒权限设置（Capability.FULL_SCREEN_INTENT_NEEDS_GRANT 时出现）。 */
    public fun openFullScreenIntentSettings()

    /** 系统精确闹钟授权页（Capability.EXACT_ALARM_NEEDS_GRANT 时出现）。 */
    public fun openExactAlarmSettings()

    /** 系统电池优化白名单申请。 */
    public fun openBatteryOptimizationSettings()

    /** 厂商后台设置页（[componentSpec] 为 `component:pkg/cls`，来自 DeviceQuirks `autostartIntent`）。 */
    public fun openVendorSettings(componentSpec: String)

    /**
     * 引导收尾后进入主界面。
     *
     * @param openShellPairing 是否接着打开注入通道（无线调试配对）引导页（可跳过）。
     *   首次引导点"开始使用"时为 true；老用户升级后同意协议（ADR-0033）时为 false，不重复引导。
     */
    public fun finishOnboarding(openShellPairing: Boolean)
}
