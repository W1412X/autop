/**
 * `onboarding` 路由图挂载入口（docs/tech/08 §3）：app 的 NavHost 调用。
 * 路由带可选 `mode` 参数（docs/tech/08 §4.7）：`onboarding`（首次引导）与
 * `onboarding?mode=health`（健康检查，同一页面、无底部按钮）。
 */
package com.example.autop.feature.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
    agreementViewModel: AgreementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val agreement by agreementViewModel.uiState.collectAsStateWithLifecycle()
    // 回到前台（ON_RESUME）重新检测所有状态（docs/tech/08 §4.7）。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) navigator.finishOnboarding(openShellPairing = true)
    }
    // 老用户（引导已完成）升级后只需再同意一次协议，同意后直接进入主界面（ADR-0033）。
    // 健康检查模式（"我的"→权限检查）下协议早已同意、引导早已完成，条件恒成立，必须排除，
    // 否则页面一打开就被收尾导航替换掉。
    LaunchedEffect(agreement, healthMode) {
        val current = agreement ?: return@LaunchedEffect
        if (!healthMode && current.agreed && current.completedBefore) {
            navigator.finishOnboarding(openShellPairing = false)
        }
    }
    val activity = LocalContext.current.findActivity()
    val current = agreement
    when {
        current == null -> Unit
        !current.agreed ->
            AgreementScreen(
                onAgree = agreementViewModel::accept,
                onDecline = { activity?.finish() },
            )
        else ->
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
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun openCheck(
    navigator: OnboardingNavigator,
    id: CheckId,
    state: OnboardingUiState,
) {
    when (id) {
        CheckId.MODEL -> navigator.openAppPage(OnboardingNavigator.AppPage.MODEL)
        CheckId.ACCESSIBILITY -> navigator.openAccessibilitySettings()
        CheckId.OVERLAY -> navigator.openOverlaySettings()
        CheckId.NOTIFICATIONS -> navigator.openNotificationSettings()
        CheckId.NOTIFICATION_LISTENER -> navigator.openNotificationListenerSettings()
        CheckId.FULL_SCREEN_INTENT -> navigator.openFullScreenIntentSettings()
        CheckId.EXACT_ALARM -> navigator.openExactAlarmSettings()
        CheckId.BATTERY_OPTIMIZATIONS -> navigator.openBatteryOptimizationSettings()
        CheckId.VENDOR_BACKGROUND -> state.vendorComponentSpec?.let { navigator.openVendorSettings(it) }
    }
}
