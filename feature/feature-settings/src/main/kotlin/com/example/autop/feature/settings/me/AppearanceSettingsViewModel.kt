/**
 * "我的"页 ViewModel（docs/tech/08 §4.5、docs/standards/coding-standards §8）。
 *
 * 读：[ConfigProvider.observe] 的各配置段（配置更新即时反映到状态与主题）、模型分组
 * 入口副标题（主模型名、向量提供方）与健康状态（无障碍 / 通知监听 / 前台服务 / 主模型）；
 * 写：[ConfigEditor.setOverride] 在各路径写用户覆盖；备份：导出/导入经
 * `me/backup` 的编排器（MemoryStore / TaskRepository / ModelProfileStore）。
 */
package com.example.autop.feature.settings.me

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.NotificationFeed
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.feature.settings.me.backup.BackupExporter
import com.example.autop.feature.settings.me.backup.BackupFile
import com.example.autop.feature.settings.me.backup.BackupImporter
import com.example.autop.feature.settings.me.backup.readBackupText
import com.example.autop.feature.settings.me.backup.writeBackupToDownloads
import com.example.autop.llm.ModelProfileStore
import com.example.autop.memory.MemoryStore
import com.example.autop.task.AgentHostStatus
import com.example.autop.task.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList") // 健康状态三来源、备份三域与配置读写、文件 IO 均为必需
public class AppearanceSettingsViewModel
    @Inject
    constructor(
        configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val store: ModelProfileStore,
        private val accessibilityStatus: AccessibilityStatus,
        private val notificationFeed: NotificationFeed,
        private val shellInput: ShellInput,
        private val permissionChecks: PermissionChecks,
        private val hostStatus: AgentHostStatus,
        private val memoryStore: MemoryStore,
        private val taskRepository: TaskRepository,
        @ApplicationContext private val context: Context,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val notice = MutableStateFlow<NoticeKind?>(null)

        /** ON_RESUME 回到前台时 +1，触发权限类同步查询重新评估（N190）。 */
        private val refreshTick = MutableStateFlow(0)

        public val uiState: StateFlow<MeUiState> =
            combine(
                configProvider.observe(),
                store.profiles,
                store.roles,
                notice,
                combine(
                    refreshTick,
                    accessibilityStatus.connected,
                    notificationFeed.connected,
                    hostStatus.running,
                    shellInput.state,
                ) { _, accessibility, _, host, shell ->
                    // 通知使用权查系统授权名单（连接态只作重评触发器，N190）。
                    HealthSignals(accessibility, permissionChecks.canReadNotifications(), host, shell)
                },
            ) { snapshot, profiles, roles, pending, health ->
                val reminder = snapshot[TaskConfig.REMINDER]
                MeUiState(
                    appearance = snapshot[UiConfig.APPEARANCE],
                    bannerDurationMs = snapshot[UiConfig.BANNER].durationMs,
                    notice = pending,
                    mainModelName = roles.main?.let { id -> profiles.firstOrNull { it.id == id }?.displayName },
                    embeddingProviderId = snapshot[LlmConfig.EMBEDDING].provider,
                    overlayWindowType = snapshot[UiConfig.OVERLAY].windowType,
                    overlayEnabled = snapshot[UiConfig.OVERLAY].enabled,
                    overlayDisplayMode = snapshot[UiConfig.OVERLAY].displayMode,
                    overlayOpacity = snapshot[UiConfig.OVERLAY].opacity,
                    hostPersistent = snapshot[TaskConfig.HOST].persistent,
                    returnToOriginApp = snapshot[TaskConfig.TASK].returnToOriginApp,
                    conditionsEnabled = snapshot[TaskConfig.CONDITIONS].enabled,
                    shieldEnabled = snapshot[DeviceConfig.TOUCH].shieldEnabled,
                    snoozeMinutes = reminder.snoozeMinutes,
                    snoozeOptions = reminder.snoozeOptions,
                    reminderExpireSchedule = reminder.expireMinutes.schedule,
                    reminderExpireCondition = reminder.expireMinutes.condition,
                    shellChannel = health.shell,
                    accessibilityConnected = health.accessibility,
                    notificationListenerGranted = health.notifications,
                    hostRunning = health.host,
                    mainModelAssigned = roles.main != null,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = MeUiState(),
            )

        /** 回到前台（ON_RESUME）重新评估权限状态；已授权未连接时请求重绑（N190）。 */
        public fun refresh() {
            if (permissionChecks.canReadNotifications() && !notificationFeed.connected.value) {
                notificationFeed.requestRebind()
            }
            refreshTick.value = refreshTick.value + 1
        }

        @Suppress("CyclomaticComplexMethod") // MeAction 逐条映射配置写入，拆分反而分散
        public fun onAction(action: MeAction) {
            when (action) {
                is MeAction.SetDarkMode ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_DARK_MODE, JsonPrimitive(action.value))
                is MeAction.SetDynamicColor ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_DYNAMIC_COLOR, JsonPrimitive(action.enabled))
                is MeAction.SetSeedIndex ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_SEED_INDEX, JsonPrimitive(action.value))
                is MeAction.SetFontScale ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_FONT_SCALE, JsonPrimitive(action.value))
                is MeAction.SetIconScale ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_ICON_SCALE, JsonPrimitive(action.value))
                is MeAction.SetDensity -> write(UiConfig.APPEARANCE.path + SUFFIX_DENSITY, JsonPrimitive(action.value))
                is MeAction.SetMotionEnabled ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_MOTION_ENABLED, JsonPrimitive(action.enabled))
                is MeAction.SetLanguage ->
                    write(UiConfig.APPEARANCE.path + SUFFIX_LANGUAGE, JsonPrimitive(action.value))
                is MeAction.SetOverlayWindowType -> write(OVERLAY_WINDOW_TYPE_PATH, JsonPrimitive(action.value))
                is MeAction.SetOverlayEnabled -> write(OVERLAY_ENABLED_PATH, JsonPrimitive(action.enabled))
                is MeAction.SetOverlayDisplayMode -> write(OVERLAY_DISPLAY_MODE_PATH, JsonPrimitive(action.value))
                is MeAction.SetOverlayOpacity -> write(OVERLAY_OPACITY_PATH, JsonPrimitive(action.value))
                is MeAction.SetHostPersistent -> write(HOST_PERSISTENT_PATH, JsonPrimitive(action.enabled))
                is MeAction.SetReturnToOriginApp -> write(RETURN_TO_ORIGIN_PATH, JsonPrimitive(action.enabled))
                is MeAction.SetConditionsEnabled -> write(CONDITIONS_ENABLED_PATH, JsonPrimitive(action.enabled))
                is MeAction.SetShieldEnabled -> write(SHIELD_ENABLED_PATH, JsonPrimitive(action.enabled))
                is MeAction.SetSnoozeMinutes -> write(SNOOZE_MINUTES_PATH, JsonPrimitive(action.value))
                MeAction.ExportData -> export()
                is MeAction.ImportData -> import(action.uri)
                is MeAction.OpenSection -> if (!action.section.hasPage) notice.value = NoticeKind.PLACEHOLDER
                // 路由层转 SettingsNavigator.openReminderSound
                MeAction.OpenReminderSound -> Unit
                MeAction.DismissNotice -> notice.value = null
            }
        }

        private fun write(
            path: String,
            value: JsonElement,
        ) {
            viewModelScope.launch(dispatchers.io) {
                configEditor.setOverride(path, value).onErr { error ->
                    logger.e(
                        tag = TAG,
                        event = "appearance_write_failed",
                        "path" to path,
                        "code" to error.code,
                        error = error.cause,
                    )
                }
            }
        }

        /** 导出全部数据到 `Download/autop/`（不含密钥，docs/tech/08 §4.5）。 */
        private fun export() {
            viewModelScope.launch(dispatchers.io) {
                val bundle =
                    BackupExporter(memoryStore, taskRepository, store, clock).export()
                val text = AutopJson.pretty.encodeToString(BackupFile.serializer(), bundle)
                notice.value =
                    when (val result = writeBackupToDownloads(context, text, clock.nowMillis())) {
                        is Outcome.Ok -> NoticeKind.EXPORTED
                        is Outcome.Err -> {
                            logger.e(tag = TAG, event = "backup_export_failed", "code" to result.error.code)
                            NoticeKind.EXPORT_FAILED
                        }
                    }
            }
        }

        /** 从 ACTION_OPEN_DOCUMENT 的 [uri] 导入（恢复记忆、任务与模型配置）。 */
        private fun import(uri: String) {
            viewModelScope.launch(dispatchers.io) {
                val outcome =
                    when (val text = readBackupText(context, Uri.parse(uri))) {
                        is Outcome.Ok ->
                            BackupImporter(memoryStore, taskRepository, store).import(text.value)
                        is Outcome.Err -> text
                    }
                notice.value =
                    when (val result = outcome) {
                        is Outcome.Ok -> NoticeKind.IMPORTED
                        is Outcome.Err -> {
                            logger.e(tag = TAG, event = "backup_import_failed", "code" to result.error.code)
                            NoticeKind.IMPORT_FAILED
                        }
                    }
            }
        }

        private companion object {
            const val TAG = "MeSettings"
            const val SUFFIX_DARK_MODE = ".darkMode"
            const val SUFFIX_DYNAMIC_COLOR = ".dynamicColor"
            const val SUFFIX_SEED_INDEX = ".seedIndex"
            const val SUFFIX_FONT_SCALE = ".fontScale"
            const val SUFFIX_ICON_SCALE = ".iconScale"
            const val SUFFIX_DENSITY = ".density"
            const val SUFFIX_MOTION_ENABLED = ".motionEnabled"
            const val SUFFIX_LANGUAGE = ".language"

            /** 悬浮窗通道写入路径（ui.overlay.windowType，docs/tech/05 §10）。 */
            const val OVERLAY_WINDOW_TYPE_PATH = "ui.overlay.windowType"

            /** 悬浮球显隐写入路径（ui.overlay.enabled）。 */
            const val OVERLAY_ENABLED_PATH = "ui.overlay.enabled"

            /** 悬浮球显示方式写入路径（ui.overlay.displayMode）。 */
            const val OVERLAY_DISPLAY_MODE_PATH = "ui.overlay.displayMode"

            /** 悬浮球透明度写入路径（ui.overlay.opacity）。 */
            const val OVERLAY_OPACITY_PATH = "ui.overlay.opacity"

            /** 常驻后台写入路径（task.host.persistent，docs/tech/07 §4.5）。 */
            const val HOST_PERSISTENT_PATH = "task.host.persistent"

            /** 任务结束后返回原应用写入路径（task.returnToOriginApp）。 */
            const val RETURN_TO_ORIGIN_PATH = "task.returnToOriginApp"

            /** 条件触发总开关写入路径（task.conditions.enabled）。 */
            const val CONDITIONS_ENABLED_PATH = "task.conditions.enabled"

            /** 执行期触摸盾总开关写入路径（device.touch.shieldEnabled，N215）。 */
            const val SHIELD_ENABLED_PATH = "device.touch.shieldEnabled"

            /** 默认稍后写入路径（task.reminder.snoozeMinutes，docs/tech/07 §4.4）。 */
            const val SNOOZE_MINUTES_PATH = "task.reminder.snoozeMinutes"
        }
    }

/** 健康信号（N190/N200）：无障碍/通知使用权/常驻服务/shell 注入通道。 */
private data class HealthSignals(
    val accessibility: Boolean,
    val notifications: Boolean,
    val host: Boolean,
    val shell: ShellState,
)
