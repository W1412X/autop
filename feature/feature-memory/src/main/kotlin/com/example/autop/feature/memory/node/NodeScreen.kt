/**
 * 条目编辑页（docs/tech/08 §4.4）：标题 / 摘要 / 正文 / 标签编辑、保存，
 * agent 来源时顶部提示可确认或撤销，底部信息行（来源、版本、更新时间）。
 */
package com.example.autop.feature.memory.node

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ErrorState
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.memory.R
import com.example.autop.memory.VectorStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 条目编辑页。 */
@Composable
public fun NodeScreen(
    state: NodeUiState,
    onAction: (NodeAction) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(
            title = heading(state),
            onBack = onBack,
            actions = {
                if (!state.isNew) {
                    AutopIconButton(
                        icon = AutopIcons.Schedule,
                        contentDescription = stringResource(R.string.memory_version_history),
                        onClick = { onAction(NodeAction.ShowVersions) },
                    )
                    AutopIconButton(
                        icon = AutopIcons.MoreVert,
                        contentDescription = stringResource(R.string.memory_more),
                        onClick = { onAction(NodeAction.ShowMore) },
                    )
                }
            },
        )
        if (state.loading) {
            EmptyState(text = stringResource(R.string.memory_loading))
            return@Column
        }
        if (state.deleted) {
            EmptyState(text = stringResource(R.string.memory_deleted_hint))
            return@Column
        }
        if (state.error != null) {
            ErrorState(text = state.error.orEmpty(), onRetry = { onAction(NodeAction.Retry) })
        }
        if (state.byAgent) {
            AgentBanner(onAction = onAction)
        }
        NodeForm(state = state, onAction = onAction)
    }

    NodeSheets(state = state, onAction = onAction)
    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.memory_delete_title),
            text = stringResource(R.string.memory_delete_text),
            onConfirm = { onAction(NodeAction.ConfirmDelete) },
            onDismiss = { onAction(NodeAction.DismissDelete) },
            destructive = true,
        )
    }
}

@Composable
private fun NodeForm(
    state: NodeUiState,
    onAction: (NodeAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        SectionTitle(text = stringResource(R.string.memory_section_content))
        FieldInput(
            label = stringResource(R.string.memory_field_title),
            value = state.title,
            maxLines = 1,
            onAction = onAction,
            field = NodeField.TITLE,
        )
        if (state.titleInvalid) {
            Text(
                text = stringResource(R.string.memory_title_invalid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = spacing.screenEdge),
            )
        }
        SummaryField(state = state, onAction = onAction)
        if (!state.creatingDirectory) {
            FieldInput(
                label = stringResource(R.string.memory_field_content),
                value = state.content,
                maxLines = CONTENT_MAX_LINES,
                onAction = onAction,
                field = NodeField.CONTENT,
            )
        }
        SectionTitle(text = stringResource(R.string.memory_section_keywords))
        KeywordRow(state = state, onAction = onAction)
        SectionTitle(text = stringResource(R.string.memory_section_info))
        NodeInfoRows(state = state)
        AutopButton(
            text = stringResource(R.string.memory_save),
            onClick = { onAction(NodeAction.Save) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(spacing.screenEdge)
                    .padding(vertical = NODE_V_PADDING),
        )
    }
}

@Composable
private fun SummaryField(
    state: NodeUiState,
    onAction: (NodeAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    FieldInput(
        label = stringResource(R.string.memory_field_summary),
        value = state.summary,
        maxLines = 1,
        onAction = onAction,
        field = NodeField.SUMMARY,
    )
    Text(
        text = stringResource(R.string.memory_summary_chars, state.summary.length, state.summaryMaxChars),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = spacing.screenEdge, bottom = NODE_V_PADDING),
    )
}

@Composable
private fun NodeInfoRows(state: NodeUiState) {
    if (state.isNew) {
        InfoRow(
            title = stringResource(R.string.memory_info_location),
            subtitle = null,
            value = state.path,
        )
        return
    }
    InfoRow(
        title = stringResource(R.string.memory_info_source),
        subtitle = null,
        value = sourceLabel(state.byAgent),
    )
    InfoRow(
        title = stringResource(R.string.memory_info_version),
        subtitle = null,
        value = stringResource(R.string.memory_version_value, state.version),
    )
    InfoRow(
        title = stringResource(R.string.memory_info_updated),
        subtitle = null,
        value = formatTime(state.updatedAt),
    )
    InfoRow(
        title = stringResource(R.string.memory_info_vector),
        subtitle = null,
        value = vectorLabel(state),
    )
}

@Composable
private fun vectorLabel(state: NodeUiState): String =
    when (state.vectorStatus) {
        VectorStatus.VECTORIZED -> stringResource(R.string.memory_vector_done, state.version)
        VectorStatus.PENDING -> stringResource(R.string.memory_vector_pending)
        VectorStatus.DISABLED -> stringResource(R.string.memory_vector_disabled)
    }

@Composable
private fun AgentBanner(onAction: (NodeAction) -> Unit) {
    StatusBanner(
        tone = StatusTone.PAUSED,
        message = stringResource(R.string.memory_agent_banner),
        actions = {
            AutopTextButton(
                text = stringResource(R.string.memory_confirm),
                onClick = { onAction(NodeAction.Confirm) },
            )
            AutopTextButton(
                text = stringResource(R.string.memory_undo_write),
                onClick = { onAction(NodeAction.UndoLastWrite) },
            )
        },
    )
}

@Composable
private fun FieldInput(
    label: String,
    value: String,
    maxLines: Int,
    field: NodeField,
    onAction: (NodeAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = spacing.screenEdge, top = NODE_V_PADDING),
    )
    AutopInputBar(
        value = value,
        onValueChange = { onAction(NodeAction.FieldChanged(field, it)) },
        spec = InputBarSpec(placeholder = label, maxLines = maxLines),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge)
                .padding(vertical = NODE_V_PADDING / 2f),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordRow(
    state: NodeUiState,
    onAction: (NodeAction) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge),
    ) {
        state.keywords.forEach { keyword ->
            AutopChip(
                text = keyword,
                selected = true,
                onClick = { onAction(NodeAction.KeywordRemoved(keyword)) },
                modifier = Modifier.padding(end = NODE_V_PADDING / 2f),
            )
        }
        AutopChip(
            text = stringResource(R.string.memory_keyword_add),
            selected = false,
            onClick = { showAddDialog = true },
        )
    }
    if (showAddDialog) {
        KeywordAddDialog(
            onConfirm = { keyword ->
                onAction(NodeAction.KeywordAdded(keyword))
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun KeywordAddDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.memory_keyword_add),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            AutopInputBar(
                value = keyword,
                onValueChange = { keyword = it },
                spec = InputBarSpec(placeholder = stringResource(R.string.memory_keyword_name_placeholder)),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            AutopTextButton(
                text = stringResource(R.string.memory_confirm),
                onClick = { if (keyword.isNotBlank()) onConfirm(keyword.trim()) },
            )
        },
        dismissButton = {
            AutopTextButton(text = stringResource(R.string.memory_cancel), onClick = onDismiss)
        },
    )
}

@Composable
private fun heading(state: NodeUiState): String =
    when {
        state.isNew ->
            stringResource(
                if (state.creatingDirectory) R.string.memory_new_dir_title else R.string.memory_new_item_title,
            )
        else -> state.title.ifBlank { state.path }
    }

@Composable
private fun sourceLabel(byAgent: Boolean): String =
    stringResource(if (byAgent) R.string.memory_source_agent else R.string.memory_source_user)

internal fun formatTime(epochMs: Long): String =
    SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(epochMs))

private val NODE_V_PADDING = 12.dp
private const val CONTENT_MAX_LINES = 12
private const val TIME_PATTERN: String = "yyyy-MM-dd HH:mm"
