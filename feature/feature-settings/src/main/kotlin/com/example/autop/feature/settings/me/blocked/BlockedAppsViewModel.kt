/**
 * 屏蔽的应用页 ViewModel（docs/tech/08 §4.5 me/blocked-apps）。
 *
 * 读：[AppLauncher.listApps] 的已安装启动项 + `policy.blockedPackages`；
 * 写：[ConfigEditor.setOverride] 整表覆盖 `policy.blockedPackages`。
 */
package com.example.autop.feature.settings.me.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.PolicyConfig
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@HiltViewModel
public class BlockedAppsViewModel
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val appLauncher: AppLauncher,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val manualOpen = MutableStateFlow(false)
        private val manualError = MutableStateFlow(false)
        private val installed = MutableStateFlow<List<AppInfo>>(emptyList())

        public val uiState: StateFlow<BlockedAppsUiState> =
            combine(
                installed,
                configProvider.observe(),
                query,
                manualOpen,
                manualError,
            ) { apps, snapshot, q, open, bad ->
                val blocked = snapshot[PolicyConfig.POLICY].blockedPackages.toSet()
                BlockedAppsUiState(
                    apps = mergeRows(apps, blocked),
                    query = q,
                    blockedCount = blocked.size,
                    manualOpen = open,
                    manualError = bad,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = BlockedAppsUiState(),
            )

        init {
            refresh()
        }

        public fun onAction(action: BlockedAppsAction) {
            when (action) {
                is BlockedAppsAction.SetQuery -> {
                    query.value = action.value
                    refresh()
                }
                is BlockedAppsAction.Toggle -> toggle(action.packageName, action.blocked)
                BlockedAppsAction.OpenManual -> {
                    manualOpen.value = true
                    manualError.value = false
                }
                BlockedAppsAction.CloseManual -> manualOpen.value = false
                is BlockedAppsAction.AddManual -> addManual(action.packageName)
            }
        }

        private fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                val apps =
                    runCatching { appLauncher.listApps(query.value.ifBlank { null }) }
                        .onFailure { logger.w(TAG, "list_apps_failed", "query" to query.value, error = it) }
                        .getOrDefault(emptyList())
                installed.value = apps
            }
        }

        private fun toggle(
            packageName: String,
            blocked: Boolean,
        ) {
            val current = configProvider.current()[PolicyConfig.POLICY].blockedPackages
            val next = if (blocked) (current + packageName).distinct() else current - packageName
            write(next)
        }

        private fun addManual(packageName: String) {
            val trimmed = packageName.trim()
            if (!isValidPackage(trimmed)) {
                manualError.value = true
                return
            }
            toggle(trimmed, blocked = true)
            manualOpen.value = false
        }

        private fun write(packages: List<String>) {
            viewModelScope.launch(dispatchers.io) {
                configEditor.setOverride(BLOCKED_PATH, JsonArray(packages.map(::JsonPrimitive))).onErr { err ->
                    logger.e(
                        tag = TAG,
                        event = "blocked_packages_write_failed",
                        "code" to err.code,
                        error = err.cause,
                    )
                }
            }
        }

        private companion object {
            const val TAG = "BlockedApps"
            val BLOCKED_PATH: String = PolicyConfig.POLICY.path + ".blockedPackages"
        }
    }

/** 已安装启动项与手动屏蔽包名合并为列表行（手动项排在后）。 */
private fun mergeRows(
    apps: List<AppInfo>,
    blocked: Set<String>,
): List<BlockedAppRow> {
    val installedRows = apps.map { BlockedAppRow(it.packageName, it.label, it.packageName in blocked) }
    val manualRows = (blocked - apps.mapTo(mutableSetOf()) { it.packageName }).map { BlockedAppRow(it, it, true) }
    return installedRows.sortedBy { it.label.lowercase() } + manualRows.sortedBy { it.packageName }
}

/** 包名格式校验（`xxx.yyy` 形态，段以字母开头）。 */
private fun isValidPackage(name: String): Boolean = PACKAGE_PATTERN.matches(name)

private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
