/**
 * `me` 路由图挂载入口（docs/tech/08 §3）：app 的 NavHost 调用。
 * 子页走 `me/{id}`（docs/tech/08 §4.5），id 见 [SettingsSection.id]。
 */
@file:Suppress("TooManyFunctions") // 路由图各子页转接函数一一对应，拆文件无收益

package com.example.autop.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.feature.settings.debug.DebugPage
import com.example.autop.feature.settings.me.AppearanceSettingsViewModel
import com.example.autop.feature.settings.me.MeAction
import com.example.autop.feature.settings.me.MeGroupScreen
import com.example.autop.feature.settings.me.MeScreen
import com.example.autop.feature.settings.me.advanced.AdvancedScreen
import com.example.autop.feature.settings.me.advanced.AdvancedViewModel
import com.example.autop.feature.settings.me.audit.AuditScreen
import com.example.autop.feature.settings.me.audit.AuditViewModel
import com.example.autop.feature.settings.me.blocked.BlockedAppsScreen
import com.example.autop.feature.settings.me.blocked.BlockedAppsViewModel
import com.example.autop.feature.settings.me.budget.BudgetScreen
import com.example.autop.feature.settings.me.budget.BudgetViewModel
import com.example.autop.feature.settings.me.embedding.EmbeddingScreen
import com.example.autop.feature.settings.me.embedding.EmbeddingViewModel
import com.example.autop.feature.settings.me.logs.LogsScreen
import com.example.autop.feature.settings.me.logs.LogsViewModel
import com.example.autop.feature.settings.me.models.ModelsScreen
import com.example.autop.feature.settings.me.models.ModelsViewModel
import com.example.autop.feature.settings.me.observe.ObserveScreen
import com.example.autop.feature.settings.me.observe.ObserveViewModel
import com.example.autop.feature.settings.me.probe.ProbeHistoryScreen
import com.example.autop.feature.settings.me.probe.ProbeHistoryViewModel
import com.example.autop.feature.settings.me.prompts.PromptsScreen
import com.example.autop.feature.settings.me.prompts.PromptsViewModel
import com.example.autop.feature.settings.me.rules.RulesScreen
import com.example.autop.feature.settings.me.rules.RulesViewModel
import com.example.autop.feature.settings.me.shell.ShellChannelScreen
import com.example.autop.feature.settings.me.shell.ShellChannelViewModel

/** `me/{id}` 路由模板的参数名（app 的 `AutopRoutes.ME_SECTION` 组装模板时引用）。 */
public const val ME_SECTION_ARG: String = "id"

/** `me/group/{group}` 路由模板的分类参数名（app 的 `AutopRoutes.ME_GROUP` 组装模板时引用）。 */
public const val ME_GROUP_ARG: String = "group"

/** `me/group/{group}?focus={focus}` 路由模板的定位参数名（可选）。 */
public const val ME_FOCUS_ARG: String = "focus"

/**
 * 挂载"我的"页与其子页（docs/tech/08 §4.5）。
 *
 * @param route 路由（app 的 `AutopRoutes.ME`）。
 * @param sectionRoute 子页路由模板（app 的 `AutopRoutes.ME_SECTION`）。
 * @param groupRoute 二级分类路由模板（app 的 `AutopRoutes.ME_GROUP`）。
 * @param navigator 子页导航回调。
 * @param debugPage 调试页（debug 源集实现；release 为 null，docs/tech/08 §4.6）。
 */
public fun NavGraphBuilder.meGraph(
    route: String,
    sectionRoute: String,
    groupRoute: String,
    navigator: SettingsNavigator,
    debugPage: DebugPage? = null,
) {
    composable(route) {
        MeRoute(navigator = navigator)
    }
    composable(sectionRoute) { entry ->
        SectionRoute(
            sectionId = entry.arguments?.getString(ME_SECTION_ARG),
            navigator = navigator,
            debugPage = debugPage,
        )
    }
    composable(
        route = groupRoute,
        arguments =
            listOf(
                navArgument(ME_GROUP_ARG) { type = NavType.StringType },
                navArgument(ME_FOCUS_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { entry ->
        GroupRoute(
            group = MeGroup.fromId(entry.arguments?.getString(ME_GROUP_ARG)),
            focus = MeFocus.fromId(entry.arguments?.getString(ME_FOCUS_ARG)),
            navigator = navigator,
        )
    }
}

/** 一级页与二级页共用的导航分发：把页面动作转成导航回调（ViewModel 不接触导航）。 */
private fun dispatchNavigation(
    action: MeAction,
    navigator: SettingsNavigator,
) {
    when (action) {
        is MeAction.OpenSection -> navigator.openSection(action.section)
        is MeAction.OpenGroup -> navigator.openGroup(action.group, action.focus)
        is MeAction.OpenSystem -> navigator.openSystemSettings(action.target)
        MeAction.OpenReminderSound -> navigator.openReminderSound()
        else -> Unit
    }
}

/** 二级分类页：分类 id 非法时显示兜底页（与 `me/{id}` 未知分区一致）。 */
@Composable
private fun GroupRoute(
    group: MeGroup?,
    focus: MeFocus?,
    navigator: SettingsNavigator,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    if (group == null) {
        MissingSectionRoute(navigator = navigator)
        return
    }
    // 回到页面即刷：与一级页一样，状态卡所依赖的权限与服务状态可能已在系统设置里改变。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MeGroupScreen(
        group = group,
        state = state,
        focus = focus,
        onBack = navigator::navigateBack,
        onAction = { action ->
            dispatchNavigation(action, navigator)
            viewModel.onAction(action)
        },
    )
}

/** 分区 id → 子页（新增分区只加一行，控圈复杂度）。 */
private val SECTION_PAGES: Map<String, @Composable (SettingsNavigator) -> Unit> =
    mapOf(
        SettingsSection.MODEL.id to { navigator -> ModelsRoute(navigator = navigator) },
        SettingsSection.OBSERVE.id to { navigator -> ObserveRoute(navigator = navigator) },
        SettingsSection.EMBEDDING.id to { navigator -> EmbeddingRoute(navigator = navigator) },
        SettingsSection.SHELL.id to { navigator -> ShellChannelRoute(navigator = navigator) },
        SettingsSection.RULES.id to { navigator -> RulesRoute(navigator = navigator) },
        SettingsSection.BLOCKED_APPS.id to { navigator -> BlockedAppsRoute(navigator = navigator) },
        SettingsSection.AUDIT.id to { navigator -> AuditRoute(navigator = navigator) },
        SettingsSection.BUDGET.id to { navigator -> BudgetRoute(navigator = navigator) },
        SettingsSection.ADVANCED.id to { navigator -> AdvancedRoute(navigator = navigator) },
        SettingsSection.PROMPTS.id to { navigator -> PromptsRoute(navigator = navigator) },
        SettingsSection.LOGS.id to { navigator -> LogsRoute(navigator = navigator) },
        SettingsSection.PROBE.id to { navigator -> ProbeHistoryRoute(navigator = navigator) },
    )

/** `me/{id}` 子页分发（从 meGraph 拆出控圈复杂度；N207 加入口后超阈值）。 */
@Composable
private fun SectionRoute(
    sectionId: String?,
    navigator: SettingsNavigator,
    debugPage: DebugPage?,
) {
    val page = sectionId?.let(SECTION_PAGES::get)
    when {
        sectionId == SettingsSection.DEBUG.id -> DebugSection(navigator = navigator, debugPage = debugPage)
        page != null -> page(navigator)
        else -> MissingSectionRoute(navigator = navigator)
    }
}

/** 调试页转接（debug 源集实现；release 为 null 时显式提示，docs/tech/08 §4.6）。 */
@Composable
private fun DebugSection(
    navigator: SettingsNavigator,
    debugPage: DebugPage?,
) {
    if (debugPage != null) {
        debugPage.Content(onBack = navigator::navigateBack)
    } else {
        MissingSectionRoute(navigator = navigator, textRes = R.string.debug_page_unavailable)
    }
}

@Composable
private fun MeRoute(
    navigator: SettingsNavigator,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    // 回到页面即刷（N190）：权限状态是同步查询，从系统设置回来需重新评估并顺带重绑监听。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MeScreen(
        state = state,
        onAction = { action ->
            dispatchNavigation(action, navigator)
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun ModelsRoute(
    navigator: SettingsNavigator,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelsScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun ObserveRoute(
    navigator: SettingsNavigator,
    viewModel: ObserveViewModel = hiltViewModel(),
) {
    // 无障碍服务连接状态会变，回到页面即重新评估各模式可用性。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun ShellChannelRoute(
    navigator: SettingsNavigator,
    viewModel: ShellChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ShellChannelScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun EmbeddingRoute(
    navigator: SettingsNavigator,
    viewModel: EmbeddingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EmbeddingScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun RulesRoute(
    navigator: SettingsNavigator,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RulesScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun BlockedAppsRoute(
    navigator: SettingsNavigator,
    viewModel: BlockedAppsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BlockedAppsScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun AuditRoute(
    navigator: SettingsNavigator,
    viewModel: AuditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AuditScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun BudgetRoute(
    navigator: SettingsNavigator,
    viewModel: BudgetViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BudgetScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun AdvancedRoute(
    navigator: SettingsNavigator,
    viewModel: AdvancedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AdvancedScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun PromptsRoute(
    navigator: SettingsNavigator,
    viewModel: PromptsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PromptsScreen(
        state = state,
        onBack = navigator::navigateBack,
        onAction = viewModel::onAction,
    )
}

@Composable
private fun LogsRoute(
    navigator: SettingsNavigator,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    // 回到页面即刷（N184）：日志被后台 Run 持续写入，不刷就停留在上次快照。
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LogsScreen(
        state = state,
        onBack = navigator::navigateBack,
        onRefresh = viewModel::refresh,
        onToggleVerbose = viewModel::setVerboseLogging,
        onClearLogs = viewModel::clearLogs,
    )
}

/** 工具测试记录页（N207）：回到页面即同步探测历史快照（内存历史，主动拉取）。 */
@Composable
private fun ProbeHistoryRoute(
    navigator: SettingsNavigator,
    viewModel: ProbeHistoryViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProbeHistoryScreen(
        state = state,
        onBack = navigator::navigateBack,
        onRefresh = viewModel::refresh,
        onExport = viewModel::export,
    )
}

/** 未落地子页兜底（直接深链到未实现 id 时不至于白屏）。 */
@Composable
private fun MissingSectionRoute(
    navigator: SettingsNavigator,
    @androidx.annotation.StringRes textRes: Int = R.string.me_placeholder_soon,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(
            title = stringResource(textRes),
            onBack = navigator::navigateBack,
        )
        EmptyState(text = stringResource(textRes))
    }
}
