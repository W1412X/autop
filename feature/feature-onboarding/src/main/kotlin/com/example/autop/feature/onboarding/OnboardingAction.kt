/**
 * 引导与健康检查页用户操作（docs/standards/coding-standards §8：sealed interface，
 * 用户操作统一通过 `onAction(action)` 交给 ViewModel；跳转类动作由路由层转给
 * [OnboardingNavigator]）。
 */
package com.example.autop.feature.onboarding

public sealed interface OnboardingAction {
    /** 点击检查项的"去设置"（打开对应系统设置或设置页）。 */
    public data class OpenCheck(
        val id: CheckId,
    ) : OnboardingAction

    /** "开始使用"（写 `ui.onboarding.completed` 后进入主界面）。 */
    public data object Complete : OnboardingAction
}
