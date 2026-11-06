/**
 * 引导与健康检查 ViewModel（docs/tech/08 §4.7）。
 *
 * 检查项状态来源：模型配置（ModelProfileStore.roles.main）、无障碍（AccessibilityStatus）走状态流；
 * 权限类（悬浮窗/通知/通知使用权/全屏提醒/精确闹钟/电池优化）经 [PermissionChecks] 端口读系统授权状态
 * （通知使用权查授权名单而非服务连接态，N190），回到前台（ON_RESUME）时 [refresh] 重新评估，
 * 已授权但监听服务未连接时自动 [NotificationFeed.requestRebind] 重连。
 */
package com.example.autop.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Capability
import com.example.autop.core.common.DeviceQuirks
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.UiConfig
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.NotificationFeed
import com.example.autop.llm.ModelProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList") // 检查项来源各异（模型/无障碍/通知/权限/机型），逐个注入是职责所在
public class OnboardingViewModel
    @Inject
    constructor(
        private val configEditor: ConfigEditor,
        private val permissionChecks: PermissionChecks,
        private val capabilities: PlatformCapabilities,
        private val quirks: DeviceQuirks,
        accessibilityStatus: AccessibilityStatus,
        private val notificationFeed: NotificationFeed,
        modelProfiles: ModelProfileStore,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        /** ON_RESUME 回到前台时 +1，触发同步权限查询重新评估。 */
        private val refreshTick = MutableStateFlow(0)

        private val completed = MutableStateFlow(false)

        public val uiState: StateFlow<OnboardingUiState> =
            combine(
                refreshTick,
                accessibilityStatus.connected,
                notificationFeed.connected,
                modelProfiles.roles,
                completed,
            ) { _, accessibilityConnected, _, roles, done ->
                OnboardingUiState(
                    items =
                        buildItems(
                            mainModelAssigned = roles.main != null,
                            accessibilityConnected = accessibilityConnected,
                        ),
                    restrictedSettings = capabilities.has(Capability.RESTRICTED_SETTINGS),
                    vendorComponentSpec = quirks.value(QUIRK_AUTOSTART),
                    completed = done,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = OnboardingUiState(),
            )

        public fun onAction(action: OnboardingAction) {
            when (action) {
                // 跳转由路由层转给 OnboardingNavigator，回到前台时 refresh 会重新检测。
                is OnboardingAction.OpenCheck -> Unit
                OnboardingAction.Complete -> complete()
            }
        }

        /** 回到前台（ON_RESUME）重新检测所有状态（docs/tech/08 §4.7）；已授权未连接时请求重绑（N190）。 */
        public fun refresh() {
            if (permissionChecks.canReadNotifications() && !notificationFeed.connected.value) {
                notificationFeed.requestRebind()
            }
            refreshTick.value = refreshTick.value + 1
        }

        /** 检查项状态（顺序即 docs/tech/08 §4.7 的卡片顺序）。 */
        private fun buildItems(
            mainModelAssigned: Boolean,
            accessibilityConnected: Boolean,
        ): List<CheckItem> =
            listOf(
                CheckItem(CheckId.MODEL, done = mainModelAssigned, required = true, visible = true),
                CheckItem(CheckId.ACCESSIBILITY, done = accessibilityConnected, required = true, visible = true),
                CheckItem(CheckId.OVERLAY, done = permissionChecks.canDrawOverlay(), required = false, visible = true),
                CheckItem(
                    CheckId.NOTIFICATIONS,
                    done = permissionChecks.canPostNotifications(),
                    required = false,
                    visible = true,
                ),
                CheckItem(
                    CheckId.NOTIFICATION_LISTENER,
                    done = permissionChecks.canReadNotifications(),
                    required = false,
                    visible = true,
                ),
                CheckItem(
                    CheckId.FULL_SCREEN_INTENT,
                    done = permissionChecks.canUseFullScreenIntent(),
                    required = false,
                    visible = capabilities.has(Capability.FULL_SCREEN_INTENT_NEEDS_GRANT),
                ),
                CheckItem(
                    CheckId.EXACT_ALARM,
                    done = permissionChecks.canScheduleExactAlarms(),
                    required = false,
                    visible = capabilities.has(Capability.EXACT_ALARM_NEEDS_GRANT),
                ),
                CheckItem(
                    CheckId.BATTERY_OPTIMIZATIONS,
                    done = permissionChecks.isIgnoringBatteryOptimizations(),
                    required = false,
                    visible = true,
                ),
                CheckItem(
                    CheckId.VENDOR_BACKGROUND,
                    done = false,
                    required = false,
                    visible = quirks.value(QUIRK_AUTOSTART) != null,
                ),
            )

        /** 写 `ui.onboarding.completed` 后置 completed（写失败也放行，避免把用户困在引导页）。 */
        private fun complete() {
            viewModelScope.launch(dispatchers.io) {
                configEditor
                    .setOverride(COMPLETED_PATH, JsonPrimitive(true))
                    .onErr { error ->
                        logger.e(
                            tag = TAG,
                            event = "onboarding_complete_failed",
                            "path" to COMPLETED_PATH,
                            "code" to error.code,
                            error = error.cause,
                        )
                    }
                completed.value = true
            }
        }

        private companion object {
            const val TAG = "Onboarding"

            /** 引导完成标记写入路径。 */
            val COMPLETED_PATH: String = UiConfig.ONBOARDING.path + ".completed"

            /** 厂商后台设置跳转描述的 DeviceQuirks 键（docs/tech/05：`component:pkg/cls`）。 */
            const val QUIRK_AUTOSTART = "autostartIntent"
        }
    }
