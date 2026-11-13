/**
 * 新版本弹窗与自动拉起安装（全局，不属于某个页面；由 app 根组合挂载）。
 *
 * - 按版本只弹一次：选择"立即下载"或"稍后"（含点击弹窗外部）都记为已提示，
 *   持久化在 [UpdateChecker.promptedVersion]，重启后不再重复弹。
 * - Run 执行期间不弹（[RunService.observeActive] 非空时隐藏）；Run 结束后仍未提示过则弹出。
 * - 下载完成后，每个版本自动拉起一次系统安装器（同样避开 Run 执行期间，且仅在已授予
 *   "安装未知应用"权限时进行）。之后由设置页"安装更新"按钮手动触发。
 */
package com.example.autop.feature.settings.me.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.device.UpdateChecker
import com.example.autop.device.UpdateInfo
import com.example.autop.device.UpdateState
import com.example.autop.task.RunService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
public class UpdatePromptViewModel
    @Inject
    constructor(
        private val updateChecker: UpdateChecker,
        private val runService: RunService,
    ) : ViewModel() {
        /** 当前应弹出提示的新版本；null 表示不弹。 */
        public val prompt: StateFlow<UpdateInfo?> =
            combine(
                updateChecker.state,
                updateChecker.promptedVersion,
                runService.observeActive(),
            ) { state, prompted, active ->
                val available = (state as? UpdateState.Available)?.info
                available?.takeIf { it.versionName != prompted && active == null }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        init {
            viewModelScope.launch {
                combine(
                    updateChecker.state,
                    updateChecker.autoInstallVersion,
                    runService.observeActive(),
                ) { state, autoVersion, active ->
                    val ready = (state as? UpdateState.ReadyToInstall)?.info
                    ready?.takeIf { it.versionName != autoVersion && active == null }
                }.collect { info ->
                    if (info != null && updateChecker.canRequestInstall()) {
                        updateChecker.markAutoInstallAttempted(info.versionName)
                        updateChecker.install()
                    }
                }
            }
        }

        /** 弹窗选择"立即下载"：记为已提示并开始后台下载。 */
        public fun onDownloadNow(info: UpdateInfo) {
            updateChecker.markPrompted(info.versionName)
            updateChecker.download()
        }

        /** 弹窗选择"稍后"或点击弹窗外部：记为已提示，本版本不再弹。 */
        public fun onLater(info: UpdateInfo) {
            updateChecker.markPrompted(info.versionName)
        }
    }
