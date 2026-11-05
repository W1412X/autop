/**
 * 工具测试记录页 ViewModel（N207）：读 [ToolProbeService.history]（悬浮球「工具测试」的
 * 探测历史，同源内存历史），进入页面/手动刷新同步快照；「导出日志」＝探测记录 +
 * 运行日志写缓存文件后拉起系统分享（N175 同款 FileProvider）。
 */
package com.example.autop.feature.settings.me.probe

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.ToolProbeResult
import com.example.autop.agent.ToolProbeService
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
public class ProbeHistoryViewModel
    @Inject
    constructor(
        private val prober: ToolProbeService,
        private val logger: Logger,
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val results = MutableStateFlow<List<ToolProbeResult>>(emptyList())

        public val uiState: StateFlow<ProbeHistoryUiState> =
            results
                .map { ProbeHistoryUiState(results = it) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = ProbeHistoryUiState(),
                )

        /** 同步探测历史快照（进入页面与手动刷新；内存历史无变更流，主动拉取）。 */
        public fun refresh() {
            results.value = prober.history().asReversed()
        }

        /** 导出诊断日志（N207）：探测记录+运行日志写缓存文件后拉起系统分享。 */
        public fun export() {
            viewModelScope.launch(dispatchers.io + logger.exceptionHandler("ProbeHistory")) {
                runCatching {
                    val dir = File(context.cacheDir, "export").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(dir, "autop-export-$stamp.log")
                    file.writeText(prober.diagnosticsText(), Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    context.startActivity(send)
                }.onFailure { e ->
                    logger.e("ProbeHistory", "probe_export_failed", "error" to (e.message ?: e.toString()))
                }
            }
        }
    }
