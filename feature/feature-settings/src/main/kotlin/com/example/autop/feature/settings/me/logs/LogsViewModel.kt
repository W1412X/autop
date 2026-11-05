/**
 * 诊断日志页 ViewModel（docs/tech/02 §4.4、docs/tech/08 §4.5"诊断日志"）：
 * 读取 [DiagnosticLogSource] 的环形缓冲快照与 [LogFiles] 文件信息，
 * 承接 debug 详细日志开关（`ui.debug.verboseLogging`，N165）与日志文件清理。
 */
package com.example.autop.feature.settings.me.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DiagnosticLogSource
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** 诊断日志页状态。 */
public data class LogsUiState(
    val lines: List<String> = emptyList(),
    /** debug 详细日志开关（`ui.debug.verboseLogging`）。 */
    val verboseLogging: Boolean = false,
    /** 日志目录绝对路径。 */
    val logDirPath: String = "",
    /** 日志文件占用字节合计。 */
    val logFileBytes: Long = 0L,
)

@HiltViewModel
@Suppress("LongParameterList") // 诊断页装配面（快照/调度/配置读写/文件/日志）
public class LogsViewModel
    @Inject
    constructor(
        private val logSource: DiagnosticLogSource,
        private val dispatchers: DispatcherProvider,
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val logFiles: LogFiles,
        private val logger: Logger,
    ) : ViewModel() {
        private val lines = MutableStateFlow<List<String>>(emptyList())
        private val verbose = MutableStateFlow(false)
        private val fileBytes = MutableStateFlow(0L)

        public val uiState: StateFlow<LogsUiState> =
            combine(lines, verbose, fileBytes) { snapshot, verboseOn, bytes ->
                LogsUiState(
                    lines = snapshot,
                    verboseLogging = verboseOn,
                    logDirPath = logFiles.directoryPath,
                    logFileBytes = bytes,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = LogsUiState(logDirPath = logFiles.directoryPath),
            )

        init {
            refresh()
            viewModelScope.launch(dispatchers.io) {
                configProvider.observe().collect { snapshot ->
                    verbose.value = snapshot[UiConfig.DEBUG].verboseLogging
                }
            }
        }

        /** 重新取一次快照与文件占用。 */
        public fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                lines.value = logSource.snapshot()
                fileBytes.value = logFiles.totalBytes()
            }
        }

        /** 切换 debug 详细日志开关（写 `ui.debug.verboseLogging`，配置回流后 [uiState] 同步）。 */
        public fun setVerboseLogging(enabled: Boolean) {
            verbose.value = enabled
            viewModelScope.launch(dispatchers.io) {
                configEditor
                    .setOverride(UiConfig.DEBUG.path + SUFFIX_VERBOSE_LOGGING, JsonPrimitive(enabled))
                    .onErr { error ->
                        logger.e(
                            tag = TAG,
                            event = "verbose_logging_write_failed",
                            "code" to error.code,
                            error = error.cause,
                        )
                    }
            }
        }

        /** 清空日志文件（快照与文件占用一并刷新）。 */
        public fun clearLogs() {
            viewModelScope.launch(dispatchers.io) {
                logFiles.clear()
                lines.value = logSource.snapshot()
                fileBytes.value = logFiles.totalBytes()
            }
        }

        private companion object {
            const val TAG = "LogsViewModel"
            const val SUFFIX_VERBOSE_LOGGING = ".verboseLogging"
        }
    }
