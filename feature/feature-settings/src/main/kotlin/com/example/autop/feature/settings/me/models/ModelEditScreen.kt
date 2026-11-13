/**
 * 模型新建/编辑页（docs/tech/08 §4.5）：协议 OptionRow、名称、baseUrl、API Key（密码输入，
 * 已保存时只显示末 4 位）、模型名（聚焦即自动拉取服务端模型列表，下方列表点选填入，
 * 也可自行输入）、上下文窗口、最大输出、quirks（高级，可折叠）；保存后自动测试连接并
 * 弹窗展示结果与能力；删除入口。角色分配在列表页，不在本页。
 */
package com.example.autop.feature.settings.me.models

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.LoadingState
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.ToastEffect
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/**
 * 模型新建/编辑页（无状态）。
 *
 * @param state 页面状态（错误/通知以 Toast 提示、远端模型列表、删除确认）。
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
    var protocolSheet by remember { mutableStateOf(false) }
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
        Toasts(state = state, onAction = onAction)
        ModelForm(state = state, draft = draft, onAction = onAction, onOpenProtocol = { protocolSheet = true })
    }
    if (protocolSheet) {
        OptionSheet(
            title = stringResource(R.string.model_field_protocol),
            options =
                listOf(
                    OptionItem(id = PROTOCOL_OPENAI, label = stringResource(R.string.protocol_openai)),
                    OptionItem(id = PROTOCOL_ANTHROPIC, label = stringResource(R.string.protocol_anthropic)),
                ),
            selectedId = draft.protocol,
            onSelect = { id ->
                onAction(ModelsAction.SetField(DraftField.PROTOCOL, id))
                protocolSheet = false
            },
            onDismiss = { protocolSheet = false },
        )
    }
    ProbeResultDialog(state = state, onAction = onAction)
}

/** 表单主体（LazyColumn：字段区、模型名与建议列表、数值限制、quirks、删除）。 */
@Composable
private fun ModelForm(
    state: ModelsUiState,
    draft: ModelDraft,
    onAction: (ModelsAction) -> Unit,
    onOpenProtocol: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            DraftFields(
                draft = draft,
                onAction = onAction,
                onOpenProtocol = onOpenProtocol,
            )
        }
        item {
            ModelField(
                draft = draft,
                onFocus = { onAction(ModelsAction.FetchRemoteModels) },
                onAction = onAction,
            )
        }
        if (state.remoteModels.running) {
            item {
                LoadingState(
                    text = stringResource(R.string.model_fetch_running),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val models = state.remoteModels.models.orEmpty()
        if (models.isNotEmpty()) {
            item {
                ModelSuggestions(
                    models = models,
                    selected = draft.model,
                    onPick = { onAction(ModelsAction.SetField(DraftField.MODEL, it)) },
                )
            }
        }
        item { LimitsFields(draft = draft, onAction = onAction) }
        item { QuirksSection(draft = draft, onAction = onAction) }
        item { DeleteSection(state = state, onAction = onAction) }
    }
}

/** 错误与通知以 Toast 呈现（不占布局空间），发出后由对应的 Dismiss 动作清空状态。 */
@Composable
private fun Toasts(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
) {
    ToastEffect(
        message = state.error?.let { errorText(it) },
        durationMs = state.bannerDurationMs,
        onConsumed = { onAction(ModelsAction.Dismiss(DismissKind.ERROR)) },
    )
    ToastEffect(
        message = state.notice?.let { noticeText(it) },
        durationMs = state.bannerDurationMs,
        onConsumed = { onAction(ModelsAction.Dismiss(DismissKind.NOTICE)) },
    )
}

/** 表单字段区（docs/tech/08 §4.5 顺序：协议、名称、baseUrl、API Key）。 */
@Composable
private fun DraftFields(
    draft: ModelDraft,
    onAction: (ModelsAction) -> Unit,
    onOpenProtocol: () -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.model_section_basic))
        OptionRow(
            title = stringResource(R.string.model_field_protocol),
            subtitle = null,
            value = protocolLabel(draft.protocol),
            onClick = onOpenProtocol,
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
    }
}

/** 模型名输入：聚焦即自动拉取服务端模型列表（下方展示，点选填入，也可自行输入）。 */
@Composable
private fun ModelField(
    draft: ModelDraft,
    onFocus: () -> Unit,
    onAction: (ModelsAction) -> Unit,
) {
    DraftTextField(
        labelRes = R.string.model_field_model,
        value = draft.model,
        // 自动触发场景静默跳过：接口地址没填时不弹错误横幅（显式校验仍在 VM）。
        onFocus = { if (draft.baseUrl.isNotBlank()) onFocus() },
    ) {
        onAction(ModelsAction.SetField(DraftField.MODEL, it))
    }
}

/** 模型建议列表：限高内部滚动，长列表不把下方字段区顶出屏幕。 */
@Composable
private fun ModelSuggestions(
    models: List<String>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = MODEL_SUGGEST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
    ) {
        models.forEach { model ->
            ModelSuggestionRow(
                model = model,
                selected = model == selected,
                onPick = { onPick(model) },
            )
        }
    }
}

/** 模型建议行（点选填入模型名输入框）。 */
@Composable
private fun ModelSuggestionRow(
    model: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        text = model,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick)
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
    )
}

/** 数值限制字段（docs/tech/08 §4.5：上下文窗口、最大输出）。 */
@Composable
private fun LimitsFields(
    draft: ModelDraft,
    onAction: (ModelsAction) -> Unit,
) {
    Column {
        DraftTextField(R.string.model_field_context_window, draft.contextWindow) {
            onAction(ModelsAction.SetField(DraftField.CONTEXT_WINDOW, it))
        }
        DraftTextField(R.string.model_field_max_output, draft.maxOutputTokens) {
            onAction(ModelsAction.SetField(DraftField.MAX_OUTPUT_TOKENS, it))
        }
    }
}

@Composable
internal fun DraftTextField(
    @StringRes labelRes: Int,
    value: String,
    placeholder: String = "",
    password: Boolean = false,
    onFocus: (() -> Unit)? = null,
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) onFocus?.invoke() },
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

/** 模型建议列表限高（约 6 行），超出在列表内部滚动。 */
private val MODEL_SUGGEST_MAX_HEIGHT = 240.dp
private const val KEY_CONTEXT_PATTERNS: String = "contextExceededPatterns"
private const val KEY_IMAGE_MAX_EDGE: String = "imageMaxEdge"
