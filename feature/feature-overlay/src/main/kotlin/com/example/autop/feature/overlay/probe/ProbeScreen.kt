/**
 * 工具测试面板内容（docs/tech/08 §5.3，N198）：工具下拉选择（标注是否操作屏幕）、
 * 参数逐键可视化编辑（N207，见 [ArgsSection]）、执行按钮、结果列表（点击行展开看
 * 全部输出）、导出日志。纯展示+回调，状态在 [ProbeController]；
 * 注入通道（无线调试）配置在「设置」（N200）。
 */
package com.example.autop.feature.overlay.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolProbeResult
import com.example.autop.agent.ToolProbeTarget
import com.example.autop.core.ui.component.ExpandableImage
import com.example.autop.core.ui.component.JsonTreeView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 收起态结果摘要的行数（点击行展开看全部；N207）。 */
private const val SUMMARY_COLLAPSED_LINES: Int = 4

/** 工具测试面板：头部固定（N217：关闭按钮不随内容滚动），下方滚动区选择→参数→执行→结果。 */
@Composable
internal fun ProbeScreen(
    state: ProbeUiState,
    callbacks: ProbeCallbacks,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderRow(
                callbacks = callbacks,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
            )
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
            ) {
                item { ChannelHint() }
                item { ToolPicker(state.targets, state.selected, callbacks) }
                if (state.selected?.actsOnScreen == true) {
                    item {
                        Text(
                            text = "⚠ 该工具会真实操作屏幕（点击/滑动/输入/启动应用）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item { ArgsSection(state.selected, state.args, callbacks) }
                item {
                    Button(
                        onClick = callbacks.onRun,
                        enabled = !state.running && state.selected != null,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                    ) {
                        Text(if (state.running) "执行中…" else "执行（在当前界面）")
                    }
                }
                item {
                    Text(
                        text = "执行结果（新→旧，共 ${state.results.size} 条，点行展开全部）",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.results) { result -> ResultRow(result) }
            }
        }
    }
}

/** 注入通道提示（N200：配对/连接在设置页，面板只留指引）。 */
@Composable
private fun ChannelHint() {
    Text(
        text = "注入通道（无线调试）在「设置 → 注入通道」管理",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun HeaderRow(
    callbacks: ProbeCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("工具测试", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = callbacks.onExport) { Text("导出日志") }
        TextButton(onClick = callbacks.onClose) { Text("关闭") }
    }
}

@Composable
private fun ToolPicker(
    targets: List<ToolProbeTarget>,
    selected: ToolProbeTarget?,
    callbacks: ProbeCallbacks,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selected?.let { "${it.name}（${accessLabelOf(it.access)}）" } ?: "选择工具",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = target.name + if (target.actsOnScreen) " ⚠" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {
                        expanded = false
                        callbacks.onSelect(target)
                    },
                )
            }
        }
    }
}

/** 一条结果：点击行展开/收起（收起态摘要 4 行，展开看全部输出与实际参数；N207）。 */
@Composable
private fun ResultRow(result: ToolProbeResult) {
    var expanded by remember(result.atMillis, result.toolName) { mutableStateOf(false) }
    val time = remember(result.atMillis) { TIME_FORMAT.format(Date(result.atMillis)) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
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
                text = "参数",
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
            text = if (expanded) "收起" else "展开全部",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 访问类别中文标注（下拉里一眼看清读写侧）。 */
private fun accessLabelOf(access: ToolAccess): String =
    when (access) {
        ToolAccess.READ_SCREEN -> "读屏"
        ToolAccess.WRITE_SCREEN -> "写屏"
        ToolAccess.READ_MEMORY -> "读记忆"
        ToolAccess.WRITE_MEMORY -> "写记忆"
        ToolAccess.READ_SYSTEM -> "读系统"
        ToolAccess.WRITE_SYSTEM -> "写系统"
        ToolAccess.CONTROL -> "控制"
    }

/** 结果行时间格式。 */
private val TIME_FORMAT: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
