/**
 * 模型编辑页的下半部分（docs/tech/08 §4.5）：保存后自动测试结果对话框、角色分配、删除入口。
 * 与 [ModelEditScreen] 同页，拆文件控制单文件行数。
 */
package com.example.autop.feature.settings.me.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.InfoDialog
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.settings.R
import com.example.autop.llm.ModelRole
import com.example.autop.llm.ModelRoles
import com.example.autop.llm.ProbeResult

/** 该角色当前分配的 profile id（null=未分配）。 */
internal fun ModelRoles.assignedTo(role: ModelRole): String? =
    when (role) {
        ModelRole.MAIN -> main
        ModelRole.VISION -> vision
        ModelRole.LIGHT -> light
    }

/** 保存后自动测试的结果对话框：配置已保存的提示 + 逐项结果 + 能力回填提示。 */
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

/** 对话框正文：保存提示、运行中指示、三行结果（或请求级失败）、能力回填提示。 */
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
            ProbeRow(labelRes = R.string.model_probe_vision, result = report.vision, onShowDetail = onShowDetail)
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

/** 结果行（docs/tech/08 §4.5："文本 ✓ 1.2s / 工具 ✓ / 图片 ✗ 原因"；失败行可点击查看详情）。 */
@Composable
private fun ProbeRow(
    labelRes: Int,
    result: ProbeResult,
    onShowDetail: (AppError) -> Unit,
) {
    val error = result.error
    val detailable = !result.ok && error != null
    ListRow(
        title = stringResource(labelRes),
        subtitle =
            if (result.ok) {
                stringResource(R.string.model_probe_elapsed, result.elapsedMs / MS_PER_SECOND)
            } else {
                subtitleParts(
                    error?.message ?: stringResource(R.string.model_probe_status_fail),
                    if (detailable) stringResource(R.string.model_probe_detail_tap) else null,
                )
            },
        modifier = if (error != null && detailable) Modifier.clickable { onShowDetail(error) } else Modifier,
        trailing = {
            StatusChip(
                text =
                    stringResource(
                        if (result.ok) R.string.model_probe_status_ok else R.string.model_probe_status_fail,
                    ),
                tone = if (result.ok) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
            )
        },
    )
}

private const val MS_PER_SECOND: Float = 1000f

/**
 * 远端模型列表区（N164）："获取模型列表"拉取服务端可用模型 id，
 * 拉取成功后"从列表选择"弹层选择填入模型名字段（避免手填别名导致 unsupported model name）。
 */
@Composable
internal fun ModelFetchSection(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
    onOpenSheet: (EditSheet) -> Unit,
) {
    val spacing = LocalSpacing.current
    val models = state.remoteModels.models
    Column(modifier = Modifier.padding(bottom = spacing.md)) {
        AutopTonalButton(
            text = stringResource(R.string.model_fetch_models),
            onClick = { onAction(ModelsAction.FetchRemoteModels) },
            enabled = !state.remoteModels.running,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg),
        )
        if (models != null && models.isNotEmpty()) {
            OptionRow(
                title = stringResource(R.string.model_pick_remote),
                subtitle = stringResource(R.string.model_pick_remote_count, models.size),
                value = state.draft?.model.orEmpty(),
                onClick = { onOpenSheet(EditSheet.RemoteModels) },
            )
        }
    }
}

/** 角色分配区（docs/tech/08 §4.5"角色分配 OptionRow"）。 */
@Composable
internal fun RolesSection(
    state: ModelsUiState,
    onOpenSheet: (EditSheet) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.model_section_roles))
        for (role in ModelRole.entries) {
            OptionRow(
                title = stringResource(roleLabelRes(role)),
                subtitle = roleDescription(role),
                value = roleValue(state, role),
                onClick = { onOpenSheet(EditSheet.Role(role)) },
            )
        }
        Text(
            text = stringResource(R.string.model_vision_coord_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun roleDescription(role: ModelRole): String? =
    if (role == ModelRole.VISION) stringResource(R.string.model_role_vision_desc) else null

@Composable
private fun roleValue(
    state: ModelsUiState,
    role: ModelRole,
): String {
    val id = state.roles.assignedTo(role)
    if (id == null) return stringResource(R.string.model_role_unassigned)
    return state.profiles.firstOrNull { it.id == id }?.displayName ?: id
}

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
