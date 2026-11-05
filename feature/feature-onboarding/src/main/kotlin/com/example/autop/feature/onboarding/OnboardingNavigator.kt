/**
 * feature-onboarding 的导航回调（docs/tech/08 §3：feature 暴露
 * `NavGraphBuilder.onboardingGraph(navigator: OnboardingNavigator)`，
 * 系统设置跳转与路由收尾由 app 侧实现）。
 */
package com.example.autop.feature.onboarding

/** 引导与健康检查页的导航回调。 */
public interface OnboardingNavigator {
    /** 打开模型配置页（app 跳 `me/models`）。 */
    public fun openModelConfig()

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

    /** 系统电池优化白名单申请。 */
    public fun openBatteryOptimizationSettings()

    /** 厂商后台设置页（[componentSpec] 为 `component:pkg/cls`，来自 DeviceQuirks `autostartIntent`）。 */
    public fun openVendorSettings(componentSpec: String)

    /** 完成引导，进入主界面（`ui.onboarding.completed` 已由 ViewModel 写入）。 */
    public fun finishOnboarding()
}
