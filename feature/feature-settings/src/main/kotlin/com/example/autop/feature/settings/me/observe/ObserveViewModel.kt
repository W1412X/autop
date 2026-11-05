/**
 * 观测方式子页 ViewModel（docs/design/observation-modes-coordinate-actions §2.3、§7.1）：
 * 读写 `device.observe` 整段（优先级顺序与各模式开关），并展示各模式当前的能力可用性。
 */
package com.example.autop.feature.settings.me.observe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceObserveSection
import com.example.autop.device.ObserveMode
import com.example.autop.device.ObserveModeAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 观测方式子页 ViewModel。 */
@HiltViewModel
public class ObserveViewModel
    @Inject
    constructor(
        configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val availability: ObserveModeAvailability,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val section = MutableStateFlow(DeviceObserveSection())
        private val reasons = MutableStateFlow<Map<String, String?>>(emptyMap())

        public val uiState: StateFlow<ObserveUiState> =
            combine(section, reasons) { current, currentReasons -> toUiState(current, currentReasons) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = ObserveUiState(),
                )

        init {
            viewModelScope.launch(dispatchers.io) {
                configProvider.observe().collect { snapshot ->
                    val current = snapshot[DeviceConfig.OBSERVE]
                    section.value = current
                    reasons.value = reasonsOf(current)
                }
            }
            refresh()
        }

        /** 重新评估各模式可用性（回到页面时调用：无障碍服务连接状态会变）。 */
        public fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                reasons.value = reasonsOf(section.value)
            }
        }

        public fun onAction(action: ObserveAction) {
            if (action is ObserveAction.OpenModelSettings) return
            val current = section.value
            val next =
                when (action) {
                    is ObserveAction.SetEnabled -> setEnabled(current, action.modeId, action.enabled)
                    is ObserveAction.MoveUp -> move(current, action.modeId, -1)
                    is ObserveAction.MoveDown -> move(current, action.modeId, 1)
                    ObserveAction.OpenModelSettings -> current
                }
            if (next == current) return
            section.value = next
            write(next)
        }

        private fun write(next: DeviceObserveSection) {
            viewModelScope.launch(dispatchers.io) {
                configEditor
                    .setOverride(
                        DeviceConfig.OBSERVE.path,
                        AutopJson.default.encodeToJsonElement(DeviceObserveSection.serializer(), next),
                    ).onErr { error ->
                        logger.e(
                            tag = TAG,
                            event = "observe_write_failed",
                            "path" to DeviceConfig.OBSERVE.path,
                            "code" to error.code,
                            error = error.cause,
                        )
                    }
            }
        }

        private fun reasonsOf(section: DeviceObserveSection): Map<String, String?> =
            section.priority.associateWith { id ->
                ObserveMode.parse(id)?.let { availability.unavailableReason(it) }
            }

        private companion object {
            const val TAG: String = "ObserveSettings"
        }
    }

private fun toUiState(
    section: DeviceObserveSection,
    reasons: Map<String, String?>,
): ObserveUiState =
    ObserveUiState(
        items =
            section.priority.map { id ->
                ObserveModeItem(
                    id = id,
                    enabled = enabledOf(section, id),
                    unavailableReason = reasons[id],
                )
            },
    )

private fun enabledOf(
    section: DeviceObserveSection,
    modeId: String,
): Boolean =
    when (modeId) {
        "a11y" -> section.modes.a11y.enabled
        "visual" -> section.modes.visual.enabled
        "llm" -> section.modes.llm.enabled
        else -> true
    }

private fun setEnabled(
    section: DeviceObserveSection,
    modeId: String,
    enabled: Boolean,
): DeviceObserveSection {
    val modes =
        when (modeId) {
            "a11y" -> section.modes.copy(a11y = section.modes.a11y.copy(enabled = enabled))
            "visual" -> section.modes.copy(visual = section.modes.visual.copy(enabled = enabled))
            "llm" -> section.modes.copy(llm = section.modes.llm.copy(enabled = enabled))
            else -> section.modes
        }
    return section.copy(modes = modes)
}

private fun move(
    section: DeviceObserveSection,
    modeId: String,
    offset: Int,
): DeviceObserveSection {
    val priority = section.priority.toMutableList()
    val from = priority.indexOf(modeId)
    val to = from + offset
    if (from < 0 || to !in priority.indices) return section
    priority.add(to, priority.removeAt(from))
    return section.copy(priority = priority)
}
