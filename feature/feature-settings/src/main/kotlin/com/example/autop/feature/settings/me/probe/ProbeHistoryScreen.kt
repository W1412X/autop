/**
 * 工具测试记录页（N207）：悬浮球「工具测试」的探测历史（新→旧），每条点击展开看
 * 全部输出与实际参数；支持刷新与导出详细日志。入口在「我的 → 高级」。
 */
package com.example.autop.feature.settings.me.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.agent.ToolProbeResult
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ExpandableImage
import com.example.autop.core.ui.component.JsonTreeView
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 收起态摘要行数（点击行展开全部）。 */
private const val SUMMARY_COLLAPSED_LINES: Int = 4

/**
 * 工具测试记录页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onRefresh 重新同步探测历史。
 * @param onExport 导出详细日志（探测记录+运行日志）。
 */
@Composable
public fun ProbeHistoryScreen(
    state: ProbeHistoryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.me_nav_probe_history), onBack = onBack)
        Row(modifier = Modifier.fillMaxWidth()) {
            AutopTextButton(
                text = stringResource(R.string.probe_history_refresh),
                onClick = onRefresh,
                modifier = Modifier.padding(start = spacing.sm),
            )
            AutopTextButton(
                text = stringResource(R.string.probe_history_export),
                onClick = onExport,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        if (state.results.isEmpty()) {
            EmptyState(text = stringResource(R.string.probe_history_empty))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.results) { result -> ProbeHistoryRow(result) }
            }
        }
    }
}

/** 一条探测记录：点击行展开/收起（收起态摘要 4 行，展开看全部输出与实际参数）。 */
@Composable
private fun ProbeHistoryRow(result: ToolProbeResult) {
    var expanded by remember(result.atMillis, result.toolName) { mutableStateOf(false) }
    val time = remember(result.atMillis) { TIME_FORMAT.format(Date(result.atMillis)) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (result.ok) "✓" else "✗",
                color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(result.toolName, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$time ${result.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (expanded) {
            JsonTreeView(text = result.summary)
        } else {
            Text(
                text = result.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = SUMMARY_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded && result.args.isNotBlank()) {
            Text(
                text = stringResource(R.string.probe_history_args_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            JsonTreeView(text = result.args)
        }
        // N208：视觉链路调试图（检测渲染图＋逐框识别 I/O），点选放大查看。
        if (expanded) {
            result.images.forEach { img ->
                ExpandableImage(
                    fileRef = img.fileRef,
                    alt = img.alt,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Text(
            text = stringResource(if (expanded) R.string.probe_history_collapse else R.string.probe_history_expand),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 记录行时间格式。 */
private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
