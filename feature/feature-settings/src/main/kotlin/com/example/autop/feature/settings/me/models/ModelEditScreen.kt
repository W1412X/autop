/**
 * 模型新建/编辑页（docs/tech/08 §4.5）：协议 OptionRow、名称、baseUrl、API Key（密码输入，
 * 已保存时只显示末 4 位）、模型名、上下文窗口、最大输出、quirks（高级，可折叠）；
 * 保存后自动测试连接并弹窗展示结果与能力；角色分配 OptionRow；删除入口。
 */
package com.example.autop.feature.settings.me.models

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InlineBanner
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/**
 * 模型新建/编辑页（无状态）。
 *
 * @param state 页面状态（错误/提示横幅、删除确认）。
 * @param draft 当前草稿。
 * @param onBack 返回列表。
 * @param onAction 用户操作回调。
 */
@Composable
public fun ModelEditScreen(
    state: ModelsUiState,
    draft: ModelDraft,
    onBack: () -> Unit,
    onAction: (ModelsAction) -> Unit,
) {
    var sheet by remember { mutableStateOf<EditSheet?>(null) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(
            title = stringResource(if (draft.isNew) R.string.models_title_new else R.string.models_title_edit),
            onBack = onBack,
            actions = {
                AutopTextButton(
                    text = stringResource(R.string.model_save),
                    onClick = { onAction(ModelsAction.Save) },
                )
            },
        )
        Banners(state = state, onAction = onAction)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { DraftFields(draft = draft, onAction = onAction, onOpenSheet = { sheet = it }) }
            item { ModelFetchSection(state = state, onAction = onAction, onOpenSheet = { sheet = it }) }
            item { QuirksSection(draft = draft, onAction = onAction) }
            item { RolesSection(state = state, onOpenSheet = { sheet = it }) }
            item { DeleteSection(state = state, onAction = onAction) }
        }
    }
    val currentSheet = sheet
    if (currentSheet != null) {
        EditSheetContent(
            sheet = currentSheet,
            state = state,
            draft = draft,
            onSelect = { id ->
                when (currentSheet) {
                    EditSheet.Protocol -> onAction(ModelsAction.SetField(DraftField.PROTOCOL, id))
                    EditSheet.RemoteModels -> onAction(ModelsAction.SetField(DraftField.MODEL, id))
                    is EditSheet.Role -> onAction(ModelsAction.AssignRole(currentSheet.role, id.ifEmpty { null }))
                }
                sheet = null
            },
            onDismiss = { sheet = null },
        )
    }
    ProbeResultDialog(state = state, onAction = onAction)
}

@Composable
private fun Banners(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
) {
    if (state.error != null) {
        InlineBanner(
            message = errorText(state.error),
            onDismiss = { onAction(ModelsAction.Dismiss(DismissKind.ERROR)) },
            durationMs = state.bannerDurationMs,
        )
    }
    if (state.notice != null) {
        InlineBanner(
            message = noticeText(state.notice),
            onDismiss = { onAction(ModelsAction.Dismiss(DismissKind.NOTICE)) },
            durationMs = state.bannerDurationMs,
        )
    }
}

/** 表单字段区（docs/tech/08 §4.5 顺序：协议、名称、baseUrl、API Key、模型名、上下文窗口、最大输出）。 */
@Composable
private fun DraftFields(
    draft: ModelDraft,
    onAction: (ModelsAction) -> Unit,
    onOpenSheet: (EditSheet) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.model_section_basic))
        OptionRow(
            title = stringResource(R.string.model_field_protocol),
            subtitle = null,
            value = protocolLabel(draft.protocol),
            onClick = { onOpenSheet(EditSheet.Protocol) },
        )
        DraftTextField(R.string.model_field_name, draft.displayName) {
            onAction(ModelsAction.SetField(DraftField.NAME, it))
        }
        DraftTextField(R.string.model_field_base_url, draft.baseUrl) {
            onAction(ModelsAction.SetField(DraftField.BASE_URL, it))
        }
        DraftTextField(
            labelRes = R.string.model_field_api_key,
            value = draft.apiKeyInput,
            placeholder = apiKeyPlaceholder(draft),
            password = true,
        ) {
            onAction(ModelsAction.SetField(DraftField.API_KEY, it))
        }
        DraftTextField(R.string.model_field_model, draft.model) {
            onAction(ModelsAction.SetField(DraftField.MODEL, it))
        }
        DraftTextField(R.string.model_field_context_window, draft.contextWindow) {
            onAction(ModelsAction.SetField(DraftField.CONTEXT_WINDOW, it))
        }
        DraftTextField(R.string.model_field_max_output, draft.maxOutputTokens) {
            onAction(ModelsAction.SetField(DraftField.MAX_OUTPUT_TOKENS, it))
        }
    }
}

@Composable
private fun DraftTextField(
    @StringRes labelRes: Int,
    value: String,
    placeholder: String = "",
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xs),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutopInputBar(
            value = value,
            onValueChange = onValueChange,
            spec = InputBarSpec(placeholder = placeholder, password = password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** API Key 占位：已保存时只显示末 4 位（docs/tech/08 §4.5）。 */
@Composable
private fun apiKeyPlaceholder(draft: ModelDraft): String {
    val tail = draft.apiKeyTail ?: return stringResource(R.string.model_api_key_placeholder)
    return stringResource(R.string.model_api_key_saved, tail)
}

/** quirks 折叠区（docs/tech/03 §6：空值走默认，只存非空项）。 */
@Composable
private fun QuirksSection(
    draft: ModelDraft,
    onAction: (ModelsAction) -> Unit,
) {
    Column {
        ActionRow(
            title = stringResource(R.string.model_quirks_toggle),
            subtitle = null,
            onClick = { onAction(ModelsAction.ToggleQuirks) },
        )
        if (draft.quirksExpanded) {
            for (quirk in quirkRows(draft.protocol)) {
                DraftTextField(
                    labelRes = quirk.labelRes,
                    value = draft.quirks[quirk.key].orEmpty(),
                ) { value ->
                    onAction(ModelsAction.SetQuirk(quirk.key, value))
                }
            }
        }
    }
}

private data class QuirkDef(
    val key: String,
    @get:StringRes val labelRes: Int,
)

/** 可编辑 quirk（docs/tech/03 §6：两者共用 + 按协议）。 */
private fun quirkRows(protocol: String): List<QuirkDef> =
    buildList {
        add(QuirkDef(KEY_CONTEXT_PATTERNS, R.string.model_quirk_context_patterns))
        add(QuirkDef(KEY_IMAGE_MAX_EDGE, R.string.model_quirk_image_max_edge))
        when (protocol) {
            PROTOCOL_OPENAI -> {
                add(QuirkDef(KEY_CHAT_PATH, R.string.model_quirk_chat_path))
                add(QuirkDef(KEY_EMBEDDINGS_PATH, R.string.model_quirk_embeddings_path))
                add(QuirkDef(KEY_MODELS_PATH, R.string.model_quirk_models_path))
                add(QuirkDef(KEY_MAX_TOKENS_FIELD, R.string.model_quirk_max_tokens_field))
                add(QuirkDef(KEY_REASONING_FIELD, R.string.model_quirk_reasoning_field))
            }
            PROTOCOL_ANTHROPIC -> {
                add(QuirkDef(KEY_MESSAGES_PATH, R.string.model_quirk_messages_path))
                add(QuirkDef(KEY_MODELS_PATH, R.string.model_quirk_models_path))
                add(QuirkDef(KEY_TOOL_CHOICE_NONE, R.string.model_quirk_tool_choice_none))
            }
        }
    }

private const val KEY_CHAT_PATH: String = "chatPath"
private const val KEY_EMBEDDINGS_PATH: String = "embeddingsPath"
private const val KEY_MODELS_PATH: String = "modelsPath"
private const val KEY_MAX_TOKENS_FIELD: String = "maxTokensField"
private const val KEY_REASONING_FIELD: String = "reasoningField"
private const val KEY_MESSAGES_PATH: String = "messagesPath"
private const val KEY_TOOL_CHOICE_NONE: String = "toolChoiceNone"
private const val KEY_CONTEXT_PATTERNS: String = "contextExceededPatterns"
private const val KEY_IMAGE_MAX_EDGE: String = "imageMaxEdge"
