/**
 * `onboarding` 路由图挂载入口（docs/tech/08 §3）：app 的 NavHost 调用。
 * 路由带可选 `mode` 参数（docs/tech/08 §4.7）：`onboarding`（首次引导）与
 * `onboarding?mode=health`（健康检查，同一页面、无底部按钮）。
 */
package com.example.autop.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/** `mode` 参数名（app 组装路由模板时引用）。 */
public const val ONBOARDING_MODE_ARG: String = "mode"

/** health 模式参数值（`onboarding?mode=health`，docs/tech/08 §4.5 权限检查入口）。 */
public const val ONBOARDING_MODE_HEALTH: String = "health"

/** 引导模式参数值（默认）。 */
public const val ONBOARDING_MODE_SETUP: String = "setup"

/**
 * 挂载引导与健康检查页（docs/tech/08 §4.7）。
 *
 * @param route 路由模板（app 的 `AutopRoutes.ONBOARDING_ROUTE`）。
 * @param navigator 跳转与收尾回调。
 */
public fun NavGraphBuilder.onboardingGraph(
    route: String,
    navigator: OnboardingNavigator,
) {
    composable(
        route = route,
        arguments =
            listOf(
                navArgument(ONBOARDING_MODE_ARG) {
                    type = NavType.StringType
                    defaultValue = ONBOARDING_MODE_SETUP
                },
            ),
    ) { entry ->
        OnboardingRoute(
            navigator = navigator,
            healthMode = entry.arguments?.getString(ONBOARDING_MODE_ARG) == ONBOARDING_MODE_HEALTH,
        )
    }
}

@Composable
private fun OnboardingRoute(
    navigator: OnboardingNavigator,
    healthMode: Boolean,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 回到前台（ON_RESUME）重新检测所有状态（docs/tech/08 §4.7）。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) navigator.finishOnboarding()
    }
    OnboardingScreen(
        state = state,
        healthMode = healthMode,
        onAction = { action ->
            when (action) {
                is OnboardingAction.OpenCheck -> openCheck(navigator, action.id, state)
                OnboardingAction.Complete -> viewModel.onAction(action)
            }
        },
    )
}

/** 检查项 → 导航回调（厂商跳转描述取自状态）。 */
private fun openCheck(
    navigator: OnboardingNavigator,
    id: CheckId,
    state: OnboardingUiState,
) {
    when (id) {
        CheckId.MODEL -> navigator.openAppPage(OnboardingNavigator.AppPage.MODEL)
        CheckId.ACCESSIBILITY -> navigator.openAccessibilitySettings()
        CheckId.SHELL_PAIRING -> navigator.openAppPage(OnboardingNavigator.AppPage.SHELL)
        CheckId.OVERLAY -> navigator.openOverlaySettings()
        CheckId.NOTIFICATIONS -> navigator.openNotificationSettings()
        CheckId.NOTIFICATION_LISTENER -> navigator.openNotificationListenerSettings()
        CheckId.FULL_SCREEN_INTENT -> navigator.openFullScreenIntentSettings()
        CheckId.EXACT_ALARM -> navigator.openExactAlarmSettings()
        CheckId.BATTERY_OPTIMIZATIONS -> navigator.openBatteryOptimizationSettings()
        CheckId.VENDOR_BACKGROUND -> state.vendorComponentSpec?.let { navigator.openVendorSettings(it) }
    }
}
