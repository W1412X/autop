/**
 * 调试页（docs/tech/08 §4.6 me/debug）：屏幕采集与统计、截图、
 * 工具试执行（见 [DebugToolSection]）、快照夹具导出；
 * "查看上次请求"区见 [RequestSection]（WP10 live 视图与 token 分布）。
 */
package com.example.autop.feature.settings.debug

import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopOutlinedButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/**
 * 调试页（无状态）。
 *
 * @param state 页面状态。
 * @param onBack 返回"我的"页。
 * @param onAction 用户操作回调。
 */
@Composable
internal fun DebugScreen(
    state: DebugUiState,
    onBack: () -> Unit,
    onAction: (DebugAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.debug_title), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { CaptureSection(state = state, onAction = onAction) }
            item { ScreenSection(state = state) }
            item { RequestSection(state = state, onAction = onAction) }
            item { ScreenshotSection(state = state, onAction = onAction) }
            item { DebugToolSection(state = state, onAction = onAction) }
            item { ExportSection(state = state, onAction = onAction) }
        }
    }
}

/** 采集入口行（倒计时 / 忙碌时给副标题提示）。 */
@Composable
private fun CaptureSection(
    state: DebugUiState,
    onAction: (DebugAction) -> Unit,
) {
    val countdown = state.countdown
    ActionRow(
        title = stringResource(R.string.debug_capture),
        subtitle = countdown?.let { stringResource(R.string.debug_capturing, it) },
        onClick = { onAction(DebugAction.Capture) },
    )
    OptionRow(
        title = stringResource(R.string.debug_capture_mode),
        subtitle = null,
        value = stringResource(observeModeText(state.observeMode)),
        onClick = {
            onAction(DebugAction.SetObserveMode(if (state.observeMode == MODE_A11Y) MODE_VISUAL else MODE_A11Y))
        },
    )
    val error = state.error
    if (error != null) {
        Text(
            text = stringResource(errorTextRes(error)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalSpacing.current.lg),
        )
    }
}

/** 采集模式显示文案。 */
@Composable
private fun observeModeText(mode: String): Int =
    if (mode == MODE_A11Y) R.string.debug_capture_mode_a11y else R.string.debug_capture_mode_visual

private const val MODE_A11Y: String = "a11y"
private const val MODE_VISUAL: String = "visual"

/** 错误 → 文案资源。 */
@Composable
private fun errorTextRes(error: DebugError): Int =
    when (error) {
        DebugError.CaptureFailed -> R.string.debug_error_capture
        DebugError.ExportFailed -> R.string.debug_export_failed
        DebugError.ArgsInvalid -> R.string.debug_args_invalid
        DebugError.ToolMissing -> R.string.debug_error_tool
    }

/** 序列化文本（mono、可选中复制）与统计（节点数、可交互数、估算 token、耗时）。 */
@Composable
private fun ScreenSection(state: DebugUiState) {
    SectionTitle(text = stringResource(R.string.debug_screen_title))
    val stats = state.stats
    if (stats != null) {
        StatsRows(stats = stats)
    }
    val clipboard = LocalClipboardManager.current
    if (state.serializedText.isEmpty()) {
        Text(
            text = stringResource(R.string.debug_no_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
        )
    } else {
        SelectionContainer {
            Text(
                text = state.serializedText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LocalSpacing.current.lg),
            )
        }
        AutopTextButton(
            text = stringResource(R.string.debug_copy),
            onClick = { clipboard.setText(AnnotatedString(state.serializedText)) },
            modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
        )
    }
}

@Composable
private fun StatsRows(stats: DebugStats) {
    InfoRow(title = stringResource(R.string.debug_stat_nodes), subtitle = null, value = stats.nodeCount.toString())
    InfoRow(
        title = stringResource(R.string.debug_stat_interactive),
        subtitle = null,
        value = stats.interactiveCount.toString(),
    )
    InfoRow(title = stringResource(R.string.debug_stat_tokens), subtitle = null, value = stats.tokens.toString())
    InfoRow(
        title = stringResource(R.string.debug_stat_elapsed),
        subtitle = null,
        value = stringResource(R.string.debug_stat_elapsed_value, stats.elapsedMs),
    )
}

/** 普通截图（docs/tech/08 §4.6）。 */
@Composable
private fun ScreenshotSection(
    state: DebugUiState,
    onAction: (DebugAction) -> Unit,
) {
    AutopOutlinedButton(
        text = stringResource(R.string.debug_screenshot),
        onClick = { onAction(DebugAction.Screenshot) },
        enabled = !state.busy && state.countdown == null,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.lg),
    )
    val path = state.screenshotPath
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.debug_screenshot_cd),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.lg),
        )
    } else {
        EmptyHint(textRes = R.string.debug_screenshot_empty)
    }
}

/** 快照夹具导出（可选脱敏）。 */
@Composable
private fun ExportSection(
    state: DebugUiState,
    onAction: (DebugAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.debug_export_title))
    SwitchRow(
        title = stringResource(R.string.debug_export_redact),
        subtitle = null,
        checked = state.redact,
        onCheckedChange = { onAction(DebugAction.SetRedact(it)) },
    )
    AutopOutlinedButton(
        text = stringResource(R.string.debug_export),
        onClick = { onAction(DebugAction.Export) },
        enabled = !state.busy,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.lg),
    )
    val exported = state.exportedFile
    if (exported != null) {
        Text(
            text = stringResource(R.string.debug_exported, exported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.lg),
        )
    }
}

/** 空态提示文字。 */
@Composable
internal fun EmptyHint(
    @StringRes textRes: Int,
) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(LocalSpacing.current.lg),
    )
}
