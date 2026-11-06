/**
 * 节点操作页 ViewModel（ADR-0015）。
 *
 * 读：[NodeActionBlockStore.blocked] 与 `device.nodeAction.enabled`，应用名取自 [AppLauncher.listApps]；
 * 写：[ConfigEditor.setOverride] 切换总开关，[NodeActionBlockStore.unblock] 恢复单个应用。
 */
package com.example.autop.feature.settings.me.nodeaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.AppLauncher
import com.example.autop.device.NodeActionBlock
import com.example.autop.device.NodeActionBlockStore
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
public class NodeActionViewModel
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val store: NodeActionBlockStore,
        private val appLauncher: AppLauncher,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val rows = MutableStateFlow<List<NodeActionBlockRow>>(emptyList())

        public val uiState: StateFlow<NodeActionUiState> =
            combine(configProvider.observe(), rows) { snapshot, blocked ->
                NodeActionUiState(enabled = snapshot[DeviceConfig.NODE_ACTION].enabled, blocked = blocked)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = NodeActionUiState(),
            )

        init {
            refresh()
        }

        public fun onAction(action: NodeActionAction) {
            when (action) {
                is NodeActionAction.SetEnabled -> setEnabled(action.enabled)
                is NodeActionAction.Restore -> restore(action.packageName)
            }
        }

        private fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                val labels =
                    runCatching { appLauncher.listApps(null) }
                        .onFailure { logger.w(TAG, "list_apps_failed", error = it) }
                        .getOrDefault(emptyList())
                        .associate { it.packageName to it.label }
                rows.value = store.blocked().map { rowOf(it, labels) }
            }
        }

        private fun setEnabled(enabled: Boolean) {
            viewModelScope.launch(dispatchers.io) {
                configEditor.setOverride(ENABLED_PATH, JsonPrimitive(enabled)).onErr { err ->
                    logger.e(
                        tag = TAG,
                        event = "node_action_enabled_write_failed",
                        "code" to err.code,
                        error = err.cause,
                    )
                }
            }
        }

        private fun restore(packageName: String) {
            viewModelScope.launch(dispatchers.io) {
                store.unblock(packageName)
                logger.i(TAG, "node_action_restored", "package" to packageName)
                refresh()
            }
        }

        private companion object {
            const val TAG = "NodeActionSettings"
            val ENABLED_PATH: String = DeviceConfig.NODE_ACTION.path + ".enabled"
        }
    }

private fun rowOf(
    block: NodeActionBlock,
    labels: Map<String, String>,
): NodeActionBlockRow =
    NodeActionBlockRow(block.packageName, labels[block.packageName] ?: block.packageName, block.blockedAt)
