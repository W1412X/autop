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
import com.example.autop.core.config.BetaFeature
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NavigationApps
import com.example.autop.device.NotificationFeed
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.UpdateChecker
import com.example.autop.device.UpdateState
import com.example.autop.feature.settings.me.backup.BackupExporter
import com.example.autop.feature.settings.me.backup.BackupFile
import com.example.autop.feature.settings.me.backup.BackupImporter
import com.example.autop.feature.settings.me.backup.readBackupText
import com.example.autop.feature.settings.me.backup.writeBackupToDownloads
import com.example.autop.llm.ModelProfile
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
        private val updateChecker: UpdateChecker,
        private val navigationApps: NavigationApps,
        private val memoryStore: MemoryStore,
        private val taskRepository: TaskRepository,
        @ApplicationContext private val context: Context,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val notice = MutableStateFlow<NoticeKind?>(null)

        private val updateNotice = MutableStateFlow<UpdateNotice?>(null)

        /** ON_RESUME 回到前台时 +1，触发权限类同步查询重新评估（N190）。 */
        private val refreshTick = MutableStateFlow(0)

        /** 导航应用选项（规则表 × 安装状态）；进入页面与回到前台时重算（intent-tool §11）。 */
        private val navigationOptions = MutableStateFlow<List<NavigationOption>>(emptyList())

        init {
            reloadNavigationApps()
        }

        // 超过 5 路的 combine 只能按 values 下标取值，下标即上方各流的声明顺序。
        @Suppress("MagicNumber")
        public val uiState: StateFlow<MeUiState> =
            combine(
                configProvider.observe(),
                store.profiles,
                store.defaultProfileId,
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
                updateChecker.state,
                updateNotice,
                navigationOptions,
            ) { values ->
                val snapshot = values[0] as ConfigSnapshot

                @Suppress("UNCHECKED_CAST")
                val profiles = values[1] as List<ModelProfile>
                val defaultId = values[2] as String?
                val pending = values[3] as NoticeKind?
                val health = values[4] as HealthSignals
                val update = values[5] as UpdateState
                val pendingUpdateNotice = values[6] as UpdateNotice?

                @Suppress("UNCHECKED_CAST")
                val nav = values[7] as List<NavigationOption>
                val reminder = snapshot[TaskConfig.REMINDER]
                MeUiState(
                    appearance = snapshot[UiConfig.APPEARANCE],
                    bannerDurationMs = snapshot[UiConfig.BANNER].durationMs,
                    notice = pending,
                    defaultModelName = profiles.firstOrNull { it.id == defaultId }?.displayName,
                    embeddingProviderId = snapshot[LlmConfig.EMBEDDING].provider,
                    overlayWindowType = snapshot[UiConfig.OVERLAY].windowType,
                    overlayEnabled = snapshot[UiConfig.OVERLAY].enabled,
                    overlayDisplayMode = snapshot[UiConfig.OVERLAY].displayMode,
                    overlayOpacity = snapshot[UiConfig.OVERLAY].opacity,
                    hostPersistent = snapshot[TaskConfig.HOST].persistent,
                    returnToOriginApp = snapshot[TaskConfig.TASK].returnToOriginApp,
                    conditionsEnabled = snapshot[TaskConfig.CONDITIONS].enabled,
                    flowsEnabled = snapshot[FeaturesConfig.FEATURES].isEnabled(BetaFeature.FLOWS),
                    shieldEnabled = snapshot[DeviceConfig.TOUCH].shieldEnabled,
                    actionDelayMs = snapshot[DeviceConfig.ACTION].delayMs,
                    snoozeMinutes = reminder.snoozeMinutes,
                    snoozeOptions = reminder.snoozeOptions,
                    reminderExpireSchedule = reminder.expireMinutes.schedule,
                    reminderExpireCondition = reminder.expireMinutes.condition,
                    shellChannel = health.shell,
                    accessibilityConnected = health.accessibility,
                    notificationListenerGranted = health.notifications,
                    hostRunning = health.host,
                    defaultModelAssigned = defaultId != null,
                    update = update,
                    updateNotice = pendingUpdateNotice,
                    navigationPreferred = snapshot[DeviceConfig.INTENT].preferredMap,
                    navigationApps = nav,
                    probeEntryVisible = snapshot[UiConfig.DEBUG].verboseLogging,
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
            reloadNavigationApps()
        }

        /** 重算导航应用的安装状态（包管理器查询在 IO 线程）。 */
        private fun reloadNavigationApps() {
            viewModelScope.launch(dispatchers.io) {
                val installed = navigationApps.installed().map { it.id }.toSet()
                navigationOptions.value =
                    navigationApps.known().map { NavigationOption(it.id, it.label, it.id in installed) }
            }
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
                is MeAction.SetBetaFeature ->
                    write(FeaturesConfig.pathOf(action.feature), JsonPrimitive(action.enabled))
                is MeAction.SetShieldEnabled -> write(SHIELD_ENABLED_PATH, JsonPrimitive(action.enabled))
                is MeAction.SetActionDelay ->
                    write(
                        ACTION_DELAY_PATH,
                        JsonPrimitive(action.seconds * MILLIS_PER_SECOND),
                    )
                is MeAction.SetSnoozeMinutes -> write(SNOOZE_MINUTES_PATH, JsonPrimitive(action.value))
                is MeAction.SetNavigationApp -> write(NAVIGATION_PREFERRED_PATH, JsonPrimitive(action.value))
                MeAction.ExportData -> export()
                is MeAction.ImportData -> import(action.uri)
                is MeAction.OpenSection -> if (!action.section.hasPage) notice.value = NoticeKind.PLACEHOLDER
                // 路由层转 SettingsNavigator.openReminderSound
                MeAction.OpenReminderSound -> Unit
                MeAction.DismissNotice -> {
                    notice.value = null
                    updateNotice.value = null
                }
                MeAction.CheckUpdate -> checkUpdate()
                MeAction.DownloadUpdate -> downloadUpdate()
                MeAction.InstallUpdate -> installUpdate()
            }
        }

        /** 手动检查更新：发现新版本由状态流驱动横幅；无更新/失败给一次性提示。 */
        private fun checkUpdate() {
            viewModelScope.launch {
                updateNotice.value = null
                when (val result = updateChecker.checkNow()) {
                    is Outcome.Ok -> if (result.value == null) updateNotice.value = UpdateNotice.UP_TO_DATE
                    is Outcome.Err -> updateNotice.value = UpdateNotice.CHECK_FAILED
                }
            }
        }

        /** 后台下载新版本 APK（进度经状态流上报）。 */
        private fun downloadUpdate() {
            viewModelScope.launch {
                updateNotice.value = null
                updateChecker.download().onErr { updateNotice.value = UpdateNotice.DOWNLOAD_FAILED }
            }
        }

        /** 拉起系统安装器；未授权时已跳授权页并提示。 */
        private fun installUpdate() {
            updateChecker.install().onErr { error ->
                if (error.code == DeviceErrorCodes.INSTALL_PERMISSION_REQUIRED) {
                    updateNotice.value = UpdateNotice.INSTALL_PERMISSION
                }
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

            /** 动作后等待写入路径（device.action.delayMs，tool_optimize 03 §5.1）。 */
            const val ACTION_DELAY_PATH = "device.action.delayMs"

            /** 秒 → 毫秒换算（动作后等待选项按秒给，配置存毫秒）。 */
            const val MILLIS_PER_SECOND = 1000

            /** 默认稍后写入路径（task.reminder.snoozeMinutes，docs/tech/07 §4.4）。 */
            const val SNOOZE_MINUTES_PATH = "task.reminder.snoozeMinutes"

            /** 导航应用偏好写入路径（device.intent.preferredMap，intent-tool §11）。 */
            const val NAVIGATION_PREFERRED_PATH = "device.intent.preferredMap"
        }
    }

/** 健康信号（N190/N200）：无障碍/通知使用权/常驻服务/shell 注入通道。 */
private data class HealthSignals(
    val accessibility: Boolean,
    val notifications: Boolean,
    val host: Boolean,
    val shell: ShellState,
)
