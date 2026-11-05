/**
 * 诊断日志页（docs/tech/02 §4.4）：debug 详细日志开关、日志文件信息与清理，
 * 等宽文本展示环形缓冲快照，支持刷新与复制全部（剪贴板）。
 */
package com.example.autop.feature.settings.me.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R
import kotlinx.coroutines.delay

/** 复制反馈提示的显示时长。 */
private const val COPIED_FEEDBACK_MILLIS = 2_000L

/** 日志占用换算 KB 的除数。 */
private const val BYTES_PER_KB = 1024L

/**
 * 诊断日志页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onRefresh 重新取快照。
 * @param onToggleVerbose 切换 debug 详细日志开关。
 * @param onClearLogs 清空日志文件。
 */
@Composable
@Suppress("LongMethod") // 诊断日志页单屏渲染，拆小函数只增跳转
public fun LogsScreen(
    state: LogsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleVerbose: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MILLIS)
            copied = false
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_nav_logs), onBack = onBack)
        SwitchRow(
            title = stringResource(R.string.logs_verbose_title),
            subtitle = stringResource(R.string.logs_verbose_subtitle),
            checked = state.verboseLogging,
            onCheckedChange = onToggleVerbose,
        )
        Text(
            text = stringResource(R.string.logs_file_info, state.logDirPath, state.logFileBytes / BYTES_PER_KB),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg, vertical = spacing.xs),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            AutopTextButton(
                text = stringResource(R.string.logs_refresh),
                onClick = onRefresh,
                modifier = Modifier.padding(start = spacing.sm),
            )
            AutopTextButton(
                text = stringResource(R.string.logs_copy),
                onClick = {
                    clipboard.setText(AnnotatedString(state.lines.joinToString(separator = "\n")))
                    copied = true
                },
                modifier = Modifier.padding(start = spacing.sm),
            )
            AutopTextButton(
                text = stringResource(R.string.logs_clear),
                onClick = onClearLogs,
                modifier = Modifier.padding(start = spacing.sm),
            )
            if (copied) {
                Text(
                    text = stringResource(R.string.logs_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(start = spacing.md)
                            .padding(top = spacing.md),
                )
            }
        }
        if (state.lines.isEmpty()) {
            EmptyState(text = stringResource(R.string.logs_empty))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.lg, vertical = spacing.xs),
                    )
                }
            }
        }
    }
}
