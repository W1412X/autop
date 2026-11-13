/**
 * 流程列表（任务页「流程」页签的内容）：草稿、流程行（统计摘要）、接口入口；长按菜单。
 */
package com.example.autop.feature.flows.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.OptionTone
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.runStatusLabel

/** 「流程」页签内容；由 app 在任务页提供给 `tasksGraph`。 */
@Composable
public fun FlowsListContent(
    navigator: FlowsNavigator,
    viewModel: FlowsListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event ==
                    Lifecycle.Event.ON_RESUME
                ) {
                    viewModel.refreshDrafts()
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    FlowsListScreen(state = state, onAction = { viewModel.onAction(it, navigator) })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FlowsListScreen(
    state: FlowsListUiState,
    onAction: (FlowsListAction) -> Unit,
) {
    var menuFlowId by remember { mutableStateOf<String?>(null) }
    var menuDraftId by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "endpoints") {
            NavRow(
                title = stringResource(R.string.flows_endpoints),
                subtitle = stringResource(R.string.flows_endpoints_hint),
                onClick = { onAction(FlowsListAction.OpenEndpoints) },
            )
        }
        if (state.drafts.isNotEmpty()) {
            item(key = "drafts-title") { SectionTitle(text = stringResource(R.string.flows_drafts)) }
            items(state.drafts, key = { "draft-" + it.draftId }) { draft ->
                ListRow(
                    title = draft.title.ifBlank { stringResource(R.string.flows_draft_untitled) },
                    subtitle = stringResource(R.string.flows_draft_hint),
                    leading = AutopIcons.Edit,
                    modifier =
                        Modifier.combinedClickable(
                            onClick = { onAction(FlowsListAction.OpenDraft(draft.draftId)) },
                            onLongClick = { menuDraftId = draft.draftId },
                        ),
                )
            }
        }
        if (state.rows.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    text = stringResource(R.string.flows_empty),
                    icon = AutopIcons.ListAlt,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            item(key = "flows-title") { SectionTitle(text = stringResource(R.string.flows_section)) }
            items(state.rows, key = { it.id }) { row ->
                ListRow(
                    title = row.name,
                    subtitle = subtitleOf(row),
                    leading = AutopIcons.ListAlt,
                    modifier =
                        Modifier.combinedClickable(
                            onClick = { onAction(FlowsListAction.Open(row.id)) },
                            onLongClick = { menuFlowId = row.id },
                        ),
                )
            }
        }
    }
    FlowMenus(
        menuFlowId = menuFlowId,
        menuDraftId = menuDraftId,
        pendingDeleteId = state.pendingDeleteId,
        onAction = onAction,
        onDismissFlow = { menuFlowId = null },
        onDismissDraft = { menuDraftId = null },
    )
}

@Composable
private fun subtitleOf(row: FlowRowUi): String {
    val parts = mutableListOf(stringResource(R.string.flows_row_nodes, row.nodeCount))
    parts += stringResource(if (row.needsScreen) R.string.flows_row_screen else R.string.flows_row_background)
    if (row.successPercent != null) {
        parts += stringResource(R.string.flows_row_success, row.successPercent, row.totalRuns)
    }
    row.lastRunStatus?.let { parts += stringResource(R.string.flows_row_last, runStatusLabel(it)) }
    return parts.joinToString(" · ")
}

@Composable
private fun flowMenuOptions(): List<OptionItem> =
    listOf(
        OptionItem(
            MENU_EDIT,
            stringResource(R.string.flows_action_edit),
            icon = OptionIcon.EDIT,
        ),
        OptionItem(
            MENU_DUPLICATE,
            stringResource(R.string.flows_action_duplicate),
            icon = OptionIcon.COPY,
        ),
        OptionItem(
            MENU_EXPORT,
            stringResource(R.string.flows_action_export),
            icon = OptionIcon.EXPORT,
        ),
        OptionItem(
            MENU_DELETE,
            stringResource(R.string.flows_action_delete),
            icon = OptionIcon.DELETE,
            tone = OptionTone.DESTRUCTIVE,
        ),
    )

@Composable
private fun draftMenuOptions(): List<OptionItem> =
    listOf(
        OptionItem(
            MENU_DELETE,
            stringResource(R.string.flows_action_delete_draft),
            icon = OptionIcon.DELETE,
            tone = OptionTone.DESTRUCTIVE,
        ),
    )

@Composable
private fun FlowMenus(
    menuFlowId: String?,
    menuDraftId: String?,
    pendingDeleteId: String?,
    onAction: (FlowsListAction) -> Unit,
    onDismissFlow: () -> Unit,
    onDismissDraft: () -> Unit,
) {
    menuFlowId?.let { id ->
        OptionSheet(
            title = null,
            options = flowMenuOptions(),
            selectedId = null,
            onSelect = { selected ->
                onDismissFlow()
                when (selected) {
                    MENU_EDIT -> onAction(FlowsListAction.Edit(id))
                    MENU_DUPLICATE -> onAction(FlowsListAction.Duplicate(id))
                    MENU_EXPORT -> onAction(FlowsListAction.Export(id))
                    MENU_DELETE -> onAction(FlowsListAction.RequestDelete(id))
                }
            },
            onDismiss = onDismissFlow,
        )
    }
    menuDraftId?.let { id ->
        OptionSheet(
            title = null,
            options = draftMenuOptions(),
            selectedId = null,
            onSelect = {
                onDismissDraft()
                onAction(FlowsListAction.DeleteDraft(id))
            },
            onDismiss = onDismissDraft,
        )
    }
    if (pendingDeleteId != null) {
        ConfirmDialog(
            title = stringResource(R.string.flows_delete_title),
            text = stringResource(R.string.flows_delete_text),
            onConfirm = { onAction(FlowsListAction.ConfirmDelete) },
            onDismiss = { onAction(FlowsListAction.DismissDelete) },
            destructive = true,
        )
    }
}

private const val MENU_EDIT = "edit"
private const val MENU_DUPLICATE = "duplicate"
private const val MENU_EXPORT = "export"
private const val MENU_DELETE = "delete"
