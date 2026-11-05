/**
 * 模型编辑页弹层（docs/tech/08 §4.5）：协议选择与角色分配选择。
 * 与 [ModelEditScreen] 同页，拆文件控制单文件行数。
 */
package com.example.autop.feature.settings.me.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.feature.settings.R
import com.example.autop.llm.ModelRole

/** 编辑页弹层类型。 */
internal sealed interface EditSheet {
    data object Protocol : EditSheet

    /** 远端模型选择（N164）。 */
    data object RemoteModels : EditSheet

    data class Role(
        val role: ModelRole,
    ) : EditSheet
}

/** 协议/角色选择弹层。 */
@Composable
internal fun EditSheetContent(
    sheet: EditSheet,
    state: ModelsUiState,
    draft: ModelDraft,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (sheet) {
        EditSheet.Protocol ->
            OptionSheet(
                title = stringResource(R.string.model_field_protocol),
                options =
                    listOf(
                        OptionItem(id = PROTOCOL_OPENAI, label = stringResource(R.string.protocol_openai)),
                        OptionItem(id = PROTOCOL_ANTHROPIC, label = stringResource(R.string.protocol_anthropic)),
                    ),
                selectedId = draft.protocol,
                onSelect = onSelect,
                onDismiss = onDismiss,
            )
        EditSheet.RemoteModels ->
            OptionSheet(
                title = stringResource(R.string.model_remote_models_title),
                options =
                    state.remoteModels.models
                        .orEmpty()
                        .map { OptionItem(id = it, label = it) },
                selectedId = draft.model,
                onSelect = onSelect,
                onDismiss = onDismiss,
            )
        is EditSheet.Role ->
            OptionSheet(
                title = stringResource(roleLabelRes(sheet.role)),
                options = roleOptions(state),
                selectedId = state.roles.assignedTo(sheet.role).orEmpty(),
                onSelect = onSelect,
                onDismiss = onDismiss,
            )
    }
}

/** 角色选项：全部 profile + "未分配"（id 为空串表示取消分配）。 */
@Composable
private fun roleOptions(state: ModelsUiState): List<OptionItem> =
    state.profiles.map { OptionItem(id = it.id, label = it.displayName) } +
        OptionItem(id = "", label = stringResource(R.string.model_role_unassigned))
