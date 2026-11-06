/**
 * 应用导航骨架（docs/tech/08 §3）：单 Activity + Navigation Compose。
 *
 * 对话列表与对话页接 feature-chat（chatGraph）、任务四路由接 feature-tasks（tasksGraph，docs/tech/08 §4.3）、
 * 记忆三页接 feature-memory（memoryGraph，docs/tech/08 §4.4）；转场为默认淡入淡出，
 * `LocalMotionEnabled = false` 时无动画（docs/tech/08 §6）。
 */
package com.example.autop

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.feature.chat.ChatNavigator
import com.example.autop.feature.chat.chatGraph
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.feature.flows.FlowsRoutes
import com.example.autop.feature.flows.flowsGraph
import com.example.autop.feature.flows.list.FlowsListContent
import com.example.autop.feature.memory.MemoryNavigator
import com.example.autop.feature.memory.memoryGraph
import com.example.autop.feature.onboarding.OnboardingNavigator
import com.example.autop.feature.onboarding.onboardingGraph
import com.example.autop.feature.settings.SettingsNavigator
import com.example.autop.feature.settings.SettingsSection
import com.example.autop.feature.settings.debug.DebugPage
import com.example.autop.feature.settings.meGraph
import com.example.autop.feature.tasks.NEW_TASK_ID
import com.example.autop.feature.tasks.TasksNavigator
import com.example.autop.feature.tasks.tasksGraph
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 顶级页路由集合（底栏仅在顶级页显示；记忆注册的是 `path` 路由模板，当前路由即模板）。 */
private val TOP_LEVEL_ROUTES: Set<String> =
    setOf(
        AutopRoutes.CHAT_LIST,
        AutopRoutes.TASKS_LIST,
        AutopRoutes.MEMORY_BROWSE,
        AutopRoutes.ME,
    )

/**
 * 应用导航骨架：[NavHost] + 顶级页底栏。
 *
 * @param showLabels 底栏是否显示文字（`ui.nav.showLabels`，默认只显示图标）。
 * @param startDestination 起始页（首次未完成引导时为 [AutopRoutes.ONBOARDING]，docs/tech/08 §4.7）。
 * @param debugPage 调试页（debug 源集提供；release 为 null，docs/tech/08 §4.6）。
 */
@Composable
internal fun AppNavHost(
    showLabels: Boolean,
    startDestination: String = AutopRoutes.CHAT_LIST,
    debugPage: DebugPage? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 转场 lambda 不是 composable 上下文，先取值再捕获。
    val motionEnabled = LocalMotionEnabled.current
    val context = LocalContext.current
    val settingsNavigator = remember(navController, context) { createSettingsNavigator(navController, context) }
    val memoryNavigator = remember(navController) { createMemoryNavigator(navController) }
    val chatNavigator = remember(navController, context) { createChatNavigator(navController, context) }
    val tasksNavigator = remember(navController) { createTasksNavigator(navController) }
    val flowsNavigator = remember(navController, context) { createFlowsNavigator(navController, context) }
    val onboardingNavigator = remember(navController, context) { createOnboardingNavigator(navController, context) }
    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = { if (motionEnabled) fadeIn() else EnterTransition.None },
            exitTransition = { if (motionEnabled) fadeOut() else ExitTransition.None },
            popEnterTransition = { if (motionEnabled) fadeIn() else EnterTransition.None },
            popExitTransition = { if (motionEnabled) fadeOut() else ExitTransition.None },
            modifier = Modifier.weight(1f),
        ) {
            chatGraph(AutopRoutes.CHAT_LIST, AutopRoutes.CHAT_THREAD, chatNavigator)
            tasksGraph(
                listRoute = AutopRoutes.TASKS_LIST,
                editRoute = AutopRoutes.TASK_EDIT,
                detailRoute = AutopRoutes.TASK_DETAIL,
                runRoute = AutopRoutes.RUN_DETAIL,
                navigator = tasksNavigator,
                flowsContent = { FlowsListContent(navigator = flowsNavigator) },
            )
            flowsGraph(FLOW_ROUTES, flowsNavigator)
            memoryGraph(
                browseRoute = AutopRoutes.MEMORY_BROWSE,
                nodeRoute = AutopRoutes.MEMORY_NODE,
                changesRoute = AutopRoutes.MEMORY_CHANGES,
                navigator = memoryNavigator,
            )
            meGraph(AutopRoutes.ME, AutopRoutes.ME_SECTION, settingsNavigator, debugPage)
            onboardingGraph(AutopRoutes.ONBOARDING_ROUTE, onboardingNavigator)
        }
        if (currentRoute in TOP_LEVEL_ROUTES) {
            AutopBottomBar(
                selectedRoute = currentRoute,
                showLabels = showLabels,
                onSelect = { tab -> navController.openTopLevel(tab.route) },
            )
        }
    }
}

/** 顶级页切换（docs/tech/08 §3）：`saveState` + `launchSingleTop` + `restoreState` 保留各 Tab 状态。 */
private fun NavHostController.openTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 顶级页底栏（docs/tech/08 §3）：固定高 [AutopDimens.navBarHeight]、默认只显示图标，
 * 底色 surface、选中图标 primary、未选中 onSurfaceVariant、指示器 primaryContainer。
 */
@Composable
private fun AutopBottomBar(
    selectedRoute: String?,
    showLabels: Boolean,
    onSelect: (TopLevelTab) -> Unit,
) {
    NavigationBar(
        modifier =
            Modifier
                .navigationBarsPadding()
                .height(AutopDimens.navBarHeight),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0),
    ) {
        TopLevelTab.entries.forEach { tab ->
            val selected = tab.route == selectedRoute || tab.routePattern == selectedRoute
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.iconSelected else tab.icon,
                        contentDescription = stringResource(tab.titleRes),
                    )
                },
                label =
                    if (showLabels) {
                        { Text(text = stringResource(tab.titleRes)) }
                    } else {
                        null
                    },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }
    }
}

/** 记忆导航回调（docs/tech/08 §4.4）：浏览 / 条目 / 最近变更跳转与返回。 */
private fun createMemoryNavigator(navController: NavHostController): MemoryNavigator =
    object : MemoryNavigator {
        override fun openBrowse(path: String) {
            navController.navigate(AutopRoutes.memoryBrowse(path))
        }

        override fun openNode(path: String) {
            navController.navigate(AutopRoutes.memoryNode(path))
        }

        override fun openChanges() {
            navController.navigate(AutopRoutes.MEMORY_CHANGES)
        }

        override fun navigateBack() {
            navController.popBackStack()
        }
    }

/** 设置导航回调（docs/tech/08 §3）：子页跳转与返回。 */
private fun createSettingsNavigator(
    navController: NavHostController,
    context: Context,
): SettingsNavigator =
    object : SettingsNavigator {
        override fun openSection(section: SettingsSection) {
            when {
                section == SettingsSection.PERMISSIONS -> navController.navigate(AutopRoutes.onboardingHealth())
                section.hasPage -> navController.navigate(AutopRoutes.meSection(section.id))
            }
        }

        override fun openReminderSound() {
            // 提醒铃声 = `reminder` 通知渠道的声音设置（渠道建于 task-impl LockReminder）。
            startSettingsSafely(
                context,
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, REMINDER_CHANNEL_ID),
            )
        }

        override fun navigateBack() {
            navController.popBackStack()
        }
    }

/** 引导与健康检查导航回调（docs/tech/08 §4.7）：模型配置跳"我的"子页，其余跳系统设置。 */
private fun createOnboardingNavigator(
    navController: NavHostController,
    context: Context,
): OnboardingNavigator =
    object : OnboardingNavigator {
        override fun openModelConfig() {
            navController.navigate(AutopRoutes.meSection(SettingsSection.MODEL.id))
        }

        override fun openAccessibilitySettings() {
            startSettingsSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        override fun openOverlaySettings() {
            startSettingsSafely(
                context,
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
        }

        override fun openNotificationSettings() {
            startSettingsSafely(
                context,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }

        override fun openNotificationListenerSettings() {
            startSettingsSafely(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        override fun openFullScreenIntentSettings() {
            // 首选全屏提醒开关页；部分 ROM 不支持时回退应用通知设置（N163）。
            startWithFallback(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }

        override fun openBatteryOptimizationSettings() {
            // 白名单申请弹窗 → 系统省电策略列表 → 应用详情页逐级回退（N163）。
            startWithFallback(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }

        /** 依序尝试 Intent 直到启动成功（厂商 ROM 对系统设置页支持不一，全部失败时记日志不崩溃）。 */
        private fun startWithFallback(vararg intents: Intent) {
            for (intent in intents) {
                runCatching {
                    context.startActivity(intent)
                    return
                }
            }
            context
                .navLogger()
                .w(TAG_NAV, "settings_fallback_exhausted", "tried" to intents.size)
        }

        override fun openVendorSettings(componentSpec: String) {
            // `component:pkg/cls` → 显式组件跳转；厂商页面不存在时记日志不崩溃（quirk 仅在已知机型给出）。
            val target = componentSpec.removePrefix("component:")
            val slash = target.indexOf('/')
            if (slash <= 0 || slash == target.lastIndex) return
            val intent =
                Intent().setComponent(
                    ComponentName(target.substring(0, slash), target.substring(slash + 1)),
                )
            runCatching { context.startActivity(intent) }
                .onFailure {
                    context.navLogger().w(TAG_NAV, "vendor_settings_unreachable", "target" to target, error = it)
                }
        }

        override fun finishOnboarding() {
            // 引导页出栈换成对话列表（避免返回键回到引导）。
            navController.navigate(AutopRoutes.CHAT_LIST) {
                navController.currentBackStackEntry
                    ?.destination
                    ?.id
                    ?.let { popUpTo(it) { inclusive = true } }
                launchSingleTop = true
            }
        }
    }

/** 对话导航回调（docs/tech/08 §3/§4.1/§4.2）：路由跳转、无障碍设置、让出屏幕与日志导出分享。 */
private fun createChatNavigator(
    navController: NavHostController,
    context: Context,
): ChatNavigator =
    object : ChatNavigator {
        override fun openThread(threadId: String) {
            navController.navigate(AutopRoutes.chatThread(threadId))
        }

        override fun replaceThread(threadId: String) {
            navController.replaceThread(threadId)
        }

        override fun openAccessibilitySettings() {
            startSettingsSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        override fun backgroundApp() {
            // 发送后让出屏幕（docs/tech/08 §4.2 ui.chat.backgroundOnSend，N126）。
            context.findActivity()?.moveTaskToBack(true)
        }

        override fun shareExport(text: String) {
            // "导出日志"（N175）：写 cache/export/ 后经 FileProvider 拉起系统分享；失败记日志不崩溃。
            runCatching {
                val dir = File(context.cacheDir, "export").apply { mkdirs() }
                // 导出文件名时间戳（autop-export-yyyyMMdd-HHmmss.log，N175）。
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "autop-export-$stamp.log")
                file.writeText(text, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                context.navLogger().w(TAG_NAV, "share_export_failed", error = it)
            }
        }

        override fun navigateBack() {
            navController.popBackStack()
        }
    }

/** 任务导航回调（docs/tech/08 §4.3）：编辑 / 详情 / Run 详情跳转与返回。 */
private fun createTasksNavigator(navController: NavHostController): TasksNavigator =
    object : TasksNavigator {
        override fun openEdit(taskId: String?) {
            navController.navigate(AutopRoutes.taskEdit(taskId ?: NEW_TASK_ID))
        }

        override fun openDetail(taskId: String) {
            navController.navigate(AutopRoutes.taskDetail(taskId))
        }

        override fun openRun(runId: String) {
            navController.navigate(AutopRoutes.runDetail(runId))
        }

        override fun openFlowCreate() {
            navController.navigate(AutopRoutes.FLOW_CREATE)
        }

        override fun navigateBack() {
            navController.popBackStack()
        }
    }

/** 流程域路由模板（由 [AutopRoutes] 集中定义）。 */
private val FLOW_ROUTES =
    FlowsRoutes(
        detail = AutopRoutes.FLOW_DETAIL,
        editor = AutopRoutes.FLOW_EDITOR,
        author = AutopRoutes.FLOW_AUTHOR,
        create = AutopRoutes.FLOW_CREATE,
        record = AutopRoutes.FLOW_RECORD,
        endpoints = AutopRoutes.FLOW_ENDPOINTS,
        state = AutopRoutes.FLOW_STATE,
    )

/** 流程导航回调：页面跳转与导出（系统分享文本）。 */
private fun createFlowsNavigator(
    navController: NavHostController,
    context: Context,
): FlowsNavigator =
    object : FlowsNavigator {
        override fun openFlow(flowId: String) {
            navController.navigate(AutopRoutes.flowDetail(flowId))
        }

        override fun openEditor(flowId: String) {
            navController.navigate(AutopRoutes.flowEditor(flowId))
        }

        override fun openAuthor(
            draftId: String?,
            editFlowId: String?,
        ) {
            navController.navigate(AutopRoutes.flowAuthor(draftId, editFlowId))
        }

        override fun openCreate() {
            navController.navigate(AutopRoutes.FLOW_CREATE)
        }

        override fun openRunRecord(runId: String) {
            navController.navigate(AutopRoutes.flowRecord(runId))
        }

        override fun openEndpoints() {
            navController.navigate(AutopRoutes.FLOW_ENDPOINTS)
        }

        override fun openState(flowId: String) {
            navController.navigate(AutopRoutes.flowState(flowId))
        }

        override fun shareText(text: String) {
            runCatching {
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { context.navLogger().w(TAG_NAV, "share_flow_failed", error = it) }
        }

        override fun navigateBack() {
            navController.popBackStack()
        }
    }

/** 提醒通知渠道 id（建于 task-impl `LockReminder.CHANNEL_ID`，铃声经渠道设置调整）。 */
private const val REMINDER_CHANNEL_ID = "reminder"

/** 新对话首次发送后替换路由（docs/tech/08 §4.2）：把"新对话"页出栈换成真实线程页。 */
private fun NavHostController.replaceThread(threadId: String) {
    navigate(AutopRoutes.chatThread(threadId)) {
        currentBackStackEntry?.destination?.id?.let { popUpTo(it) { inclusive = true } }
        launchSingleTop = true
    }
}

/** 取承载 [Context] 的 [Activity]（[LocalContext] 可能被 [ContextWrapper] 包裹）。 */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
