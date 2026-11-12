/**
 * 模型编辑页的下半部分（docs/tech/08 §4.5）：保存后自动测试结果对话框、删除入口。
 * 与 [ModelEditScreen] 同页，拆文件控制单文件行数。
 */
package com.example.autop.feature.settings.me.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.common.AppError
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.InfoDialog
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.settings.R
import com.example.autop.llm.ProbeResult
import com.example.autop.llm.ProbeVerdict

/** 保存后自动测试的结果对话框：配置已保存的提示 + 连通结果。 */
@Composable
internal fun ProbeResultDialog(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
) {
    val probe = state.probe
    if (!probe.visible) return
    var detail by remember { mutableStateOf<AppError?>(null) }
    AlertDialog(
        onDismissRequest = { onAction(ModelsAction.CloseProbe) },
        title = {
            Text(
                text = stringResource(R.string.model_probe_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            ProbeDialogBody(
                probe = probe,
                onShowDetail = { detail = it },
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = { onAction(ModelsAction.CloseProbe) },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.model_probe_finish))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(ModelsAction.RetestProbe) },
                enabled = !probe.running,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.model_probe_retest))
            }
        },
    )
    val detailError = detail
    if (detailError != null) {
        InfoDialog(
            title = stringResource(R.string.model_probe_detail_title),
            text = errorDetailText(detailError),
            onDismiss = { detail = null },
        )
    }
}

/** 对话框正文：保存提示、运行中指示、连通结果（或请求级失败）。 */
@Composable
private fun ProbeDialogBody(
    probe: ProbeUiState,
    onShowDetail: (AppError) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.model_probe_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (probe.running) {
            ProbeRunningRow()
        }
        val report = probe.report
        if (report != null) {
            ProbeRow(labelRes = R.string.model_probe_text, result = report.text, onShowDetail = onShowDetail)
            ProbeRow(labelRes = R.string.model_probe_tools, result = report.tools, onShowDetail = onShowDetail)
            Text(
                text = stringResource(R.string.model_probe_hint),
                modifier = Modifier.padding(top = spacing.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val error = probe.error
        if (error != null) {
            ListRow(
                title = stringResource(R.string.model_probe_request_failed),
                subtitle =
                    subtitleParts(
                        error.message,
                        stringResource(R.string.model_probe_detail_tap),
                    ),
                modifier = Modifier.clickable { onShowDetail(error) },
                trailing = {
                    StatusChip(
                        text = stringResource(R.string.model_probe_status_fail),
                        tone = StatusTone.BLOCKED,
                    )
                },
            )
        }
    }
}

@Composable
private fun ProbeRunningRow() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(AutopDimens.loadingIndicatorSize),
            strokeWidth = PROBE_SPINNER_STROKE,
        )
        Text(
            text = stringResource(R.string.model_probe_running),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val PROBE_SPINNER_STROKE = 2.dp

/** 结果行：成功显示耗时；未确认或失败显示原因（失败行可点击查看详情）。 */
@Composable
private fun ProbeRow(
    labelRes: Int,
    result: ProbeResult,
    onShowDetail: (AppError) -> Unit,
) {
    val error = result.error
    val detailable = !result.ok && error != null
    val inconclusive = result.verdict == ProbeVerdict.INCONCLUSIVE
    ListRow(
        title = stringResource(labelRes),
        subtitle =
            when {
                result.ok -> stringResource(R.string.model_probe_elapsed, result.elapsedMs / MS_PER_SECOND)
                inconclusive ->
                    subtitleParts(
                        stringResource(R.string.model_probe_inconclusive_note),
                        if (detailable) stringResource(R.string.model_probe_detail_tap) else null,
                    )
                else ->
                    subtitleParts(
                        error?.message ?: stringResource(R.string.model_probe_status_fail),
                        if (detailable) stringResource(R.string.model_probe_detail_tap) else null,
                    )
            },
        modifier = if (error != null && detailable) Modifier.clickable { onShowDetail(error) } else Modifier,
        trailing = {
            StatusChip(text = stringResource(statusRes(result.verdict)), tone = toneOf(result.verdict))
        },
    )
}

private fun statusRes(verdict: ProbeVerdict): Int =
    when (verdict) {
        ProbeVerdict.SUPPORTED -> R.string.model_probe_status_ok
        ProbeVerdict.UNSUPPORTED -> R.string.model_probe_status_fail
        ProbeVerdict.INCONCLUSIVE -> R.string.model_probe_status_inconclusive
    }

private fun toneOf(verdict: ProbeVerdict): StatusTone =
    when (verdict) {
        ProbeVerdict.SUPPORTED -> StatusTone.SUCCEEDED
        ProbeVerdict.UNSUPPORTED -> StatusTone.BLOCKED
        ProbeVerdict.INCONCLUSIVE -> StatusTone.WAITING
    }

private const val MS_PER_SECOND: Float = 1000f

/** 删除入口与确认框（删除同时清除已保存密钥）。 */
@Composable
internal fun DeleteSection(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
) {
    ActionRow(
        title = stringResource(R.string.model_delete),
        subtitle = null,
        onClick = { onAction(ModelsAction.RequestDelete) },
        destructive = true,
    )
    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.model_delete_confirm_title),
            text = stringResource(R.string.model_delete_confirm_text),
            onConfirm = { onAction(ModelsAction.ConfirmDelete) },
            onDismiss = { onAction(ModelsAction.Dismiss(DismissKind.DELETE_CONFIRM)) },
            destructive = true,
        )
    }
}

/** 错误横幅文案（ViewModel 不接触资源）。 */
@Composable
internal fun errorText(kind: ModelsErrorKind): String =
    when (kind) {
        ModelsErrorKind.MISSING_FIELDS -> stringResource(R.string.model_error_missing_fields)
        ModelsErrorKind.INVALID_NUMBERS -> stringResource(R.string.model_error_invalid_numbers)
        ModelsErrorKind.SAVE_FAILED -> stringResource(R.string.model_error_save_failed)
        ModelsErrorKind.LIST_MODELS_FAILED -> stringResource(R.string.model_error_list_models_failed)
    }

/** 提示横幅文案（ViewModel 不接触资源）。 */
@Composable
internal fun noticeText(kind: ModelsNoticeKind): String =
    when (kind) {
        ModelsNoticeKind.MODELS_EMPTY -> stringResource(R.string.model_notice_models_empty)
    }
