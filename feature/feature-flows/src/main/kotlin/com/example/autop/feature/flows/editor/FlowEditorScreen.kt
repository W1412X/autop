/**
 * 流程编辑器：简明（按步骤清单编辑常用字段）/ 高级（全部字段、失败处理、调试）/ 原始 JSON。
 */
@file:Suppress("LongMethod", "MagicNumber") // Compose 页面：布局声明

package com.example.autop.feature.flows.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.flows.ADDABLE_TYPES
import com.example.autop.feature.flows.ParamFields
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.childSteps
import com.example.autop.feature.flows.nodeDetail
import com.example.autop.feature.flows.nodeType
import com.example.autop.feature.flows.nodeTypeLabel
import com.example.autop.feature.flows.stringByName
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.Node

/** 编辑器页。 */
@Composable
public fun FlowEditorScreen(
    state: FlowEditorUiState,
    onAction: (FlowEditorAction) -> Unit,
) {
    val flow = state.flow
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(
            title = flow?.name ?: stringResource(R.string.editor_title),
            onBack = { onAction(FlowEditorAction.Back) },
            actions = {
                AutopIconButton(AutopIcons.Settings, stringResource(R.string.editor_settings), {
                    onAction(FlowEditorAction.OpenSettings)
                })
                AutopIconButton(
                    AutopIcons.Play,
                    stringResource(R.string.flow_action_run),
                    { onAction(FlowEditorAction.RunWhole) },
                )
                AutopTonalButton(
                    text = stringResource(R.string.flow_save),
                    onClick = { onAction(FlowEditorAction.Save) },
                )
            },
        )
        if (state.loaded && flow == null) {
            StatusBanner(
                tone = StatusTone.BLOCKED,
                message = stringResource(R.string.flow_not_found),
                modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
            )
            return@Column
        }
        if (flow == null) return@Column
        ModeTabs(mode = state.mode, onAction = onAction)
        IssueBar(state = state, onAction = onAction)
        when (state.mode) {
            EditorMode.JSON -> JsonView(state = state, onAction = onAction)
            else ->
                StepsView(
                    flow = flow,
                    state = state,
                    advanced = state.mode == EditorMode.ADVANCED,
                    onAction = onAction,
                )
        }
    }
    EditorDialogs(state = state, onAction = onAction)
}

@Composable
private fun ModeTabs(
    mode: EditorMode,
    onAction: (FlowEditorAction) -> Unit,
) {
    SecondaryTabRow(selectedTabIndex = EditorMode.entries.indexOf(mode)) {
        EditorMode.entries.forEach { item ->
            Tab(
                selected = item == mode,
                onClick = { onAction(FlowEditorAction.SetMode(item)) },
                text = {
                    Text(
                        stringResource(
                            when (item) {
                                EditorMode.SIMPLE -> R.string.editor_mode_simple
                                EditorMode.ADVANCED -> R.string.editor_mode_advanced
                                EditorMode.JSON -> R.string.editor_mode_json
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun IssueBar(
    state: FlowEditorUiState,
    onAction: (FlowEditorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    val errors = state.issues.count { it.severity == IssueSeverity.ERROR }
    val warnings = state.issues.count { it.severity == IssueSeverity.WARNING }
    if (errors == 0 && warnings == 0 && state.error == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screenEdge, vertical = spacing.sm)) {
        StatusBanner(
            tone = if (errors > 0) StatusTone.BLOCKED else StatusTone.WAITING,
            message =
                state.error?.let { errorText(it) } ?: stringResource(R.string.editor_issues_summary, errors, warnings),
            modifier = Modifier.fillMaxWidth(),
            actions = {
                AutopTextButton(
                    text =
                        stringResource(
                            if (state.showIssues) R.string.editor_issues_hide else R.string.editor_issues_show,
                        ),
                    onClick = { onAction(FlowEditorAction.ToggleIssues) },
                )
            },
        )
        if (state.showIssues) {
            state.issues.forEach { issue ->
                Text(
                    text =
                        (if (issue.severity == IssueSeverity.ERROR) "✗ " else "! ") +
                            (issue.nodeId?.let { "$it：" } ?: "") +
                            issue.message,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (issue.severity ==
                            IssueSeverity.ERROR
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun errorText(code: String): String =
    when (code) {
        FlowEditorViewModel.HAS_ERRORS -> stringResource(R.string.editor_error_has_errors)
        FlowEditorViewModel.NOT_FOUND -> stringResource(R.string.flow_not_found)
        else -> stringResource(R.string.flow_error_generic, code)
    }

@Composable
private fun JsonView(
    state: FlowEditorUiState,
    onAction: (FlowEditorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screenEdge)) {
        if (state.jsonError) {
            Text(
                text = stringResource(R.string.editor_json_invalid),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        AutopInputBar(
            value = state.jsonText,
            onValueChange = { onAction(FlowEditorAction.SetJson(it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.editor_mode_json), maxLines = JSON_VIEW_LINES),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val JSON_VIEW_LINES = 40

/** 步骤清单里的一行：节点，或某个子列表末尾的"添加"。 */
private sealed interface StepRow {
    data class Item(
        val node: Node,
        val depth: Int,
        val label: String,
    ) : StepRow

    data class Add(
        val slot: ListSlot,
        val depth: Int,
        val title: String,
    ) : StepRow
}

private fun rowsOf(flow: FlowDefinition): List<StepRow> {
    val out = mutableListOf<StepRow>()

    fun walk(
        steps: List<Node>,
        parent: Node?,
        key: String,
        depth: Int,
        prefix: String,
    ) {
        steps.forEachIndexed { i, node ->
            val label = "$prefix${i + 1}"
            out += StepRow.Item(node, depth, label)
            childSteps(node).forEach { (childKey, children) -> walk(children, node, childKey, depth + 1, "$label.") }
            if (childSteps(node).isNotEmpty()) Unit
        }
        out += StepRow.Add(ListSlot(parent?.id, key), depth, key)
    }
    walk(flow.steps, null, "root", 0, "")
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepsView(
    flow: FlowDefinition,
    state: FlowEditorUiState,
    advanced: Boolean,
    onAction: (FlowEditorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    val rows = rowsOf(flow)
    val issueNodes =
        state.issues
            .filter { it.severity == IssueSeverity.ERROR }
            .mapNotNull { it.nodeId }
            .toSet()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (flow.steps.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.editor_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.screenEdge),
                )
            }
        }
        items(rows, key = { row ->
            when (row) {
                is StepRow.Item -> "n-" + row.node.id
                is StepRow.Add -> "a-" + row.slot.parentId + "/" + row.slot.key
            }
        }) { row ->
            when (row) {
                is StepRow.Item -> {
                    val node = row.node
                    val type = nodeType(node)
                    val marks =
                        buildString {
                            if (!node.enabled) append("⏸ ")
                            if (node.id in state.breakpoints) append("● ")
                            if (node.id in issueNodes) append("✗ ")
                        }
                    ListRow(
                        title = marks + (node.name ?: nodeTypeLabel(type)),
                        subtitle =
                            buildString {
                                if (advanced) append("${node.id} · ${nodeTypeLabel(type)} · ")
                                append(nodeDetail(node))
                            }.trim().trimEnd('·').trim(),
                        leading = iconOf(type),
                        modifier =
                            Modifier
                                .padding(start = spacing.lg * row.depth)
                                .combinedClickable(
                                    onClick = { onAction(FlowEditorAction.EditNode(node.id)) },
                                    onLongClick = { onAction(FlowEditorAction.OpenMenu(node.id)) },
                                ),
                        trailing = {
                            AutopIconButton(AutopIcons.MoreVert, stringResource(R.string.editor_node_menu), {
                                onAction(FlowEditorAction.OpenMenu(node.id))
                            })
                        },
                    )
                }
                is StepRow.Add ->
                    AutopTextButton(
                        text = stringResource(R.string.editor_add_node) + addSuffix(row.title),
                        onClick = { onAction(FlowEditorAction.RequestAdd(row.slot)) },
                        modifier = Modifier.padding(start = spacing.lg * row.depth + spacing.screenEdge),
                    )
            }
        }
    }
}

@Composable
private fun addSuffix(key: String): String = if (key == "root") "" else " · " + stringByName("flow_slot_$key", key)

private fun iconOf(type: String) =
    when (type) {
        "tool" -> AutopIcons.TouchApp
        "wait" -> AutopIcons.Schedule
        "if", "switch" -> AutopIcons.OpenWith
        "loop" -> AutopIcons.Refresh
        "llm", "agent" -> AutopIcons.Psychology
        "api", "call" -> AutopIcons.Link
        "read", "observe" -> AutopIcons.Visibility
        "end" -> AutopIcons.Stop
        else -> AutopIcons.Description
    }

@Composable
private fun EditorDialogs(
    state: FlowEditorUiState,
    onAction: (FlowEditorAction) -> Unit,
) {
    state.editing?.let { NodeEditDialog(edit = it, advanced = state.mode != EditorMode.SIMPLE, onAction = onAction) }
    state.menuNodeId?.let { id -> NodeMenu(id = id, state = state, onAction = onAction) }
    state.addSlot?.let {
        OptionSheet(
            title = stringResource(R.string.editor_add_node),
            options = ADDABLE_TYPES.map { OptionItem(it, nodeTypeLabel(it)) },
            selectedId = null,
            onSelect = { onAction(FlowEditorAction.AddType(it)) },
            onDismiss = { onAction(FlowEditorAction.DismissAdd) },
        )
    }
    state.settings?.let { FlowSettingsDialog(form = it, onAction = onAction) }
    state.run?.let { RunDialog(run = it, flow = state.flow, onAction = onAction) }
    if (state.confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.editor_discard_title),
            text = stringResource(R.string.editor_discard_text),
            onConfirm = { onAction(FlowEditorAction.ConfirmDiscard) },
            onDismiss = { onAction(FlowEditorAction.DismissDiscard) },
            destructive = true,
        )
    }
}

@Composable
private fun NodeMenu(
    id: String,
    state: FlowEditorUiState,
    onAction: (FlowEditorAction) -> Unit,
) {
    val labels =
        listOf(
            MenuItems.EDIT to R.string.editor_menu_edit,
            MenuItems.UP to R.string.editor_menu_up,
            MenuItems.DOWN to R.string.editor_menu_down,
            MenuItems.ADD_AFTER to R.string.editor_menu_add_after,
            MenuItems.DUPLICATE to R.string.editor_menu_duplicate,
            MenuItems.TOGGLE_ENABLED to R.string.editor_menu_toggle,
            MenuItems.BREAKPOINT to R.string.editor_menu_breakpoint,
            MenuItems.RUN_FROM to R.string.editor_menu_run_from,
            MenuItems.RUN_ONLY to R.string.editor_menu_run_only,
            MenuItems.DELETE to R.string.editor_menu_delete,
        )
    val advanced = state.mode == EditorMode.ADVANCED
    val visible = labels.filter { advanced || it.first !in ADVANCED_ONLY }
    OptionSheet(
        title = null,
        options = visible.map { OptionItem(it.first, stringResource(it.second)) },
        selectedId = null,
        onSelect = { onAction(FlowEditorAction.Menu(id, it)) },
        onDismiss = { onAction(FlowEditorAction.DismissMenu) },
    )
}

private val ADVANCED_ONLY = setOf(MenuItems.BREAKPOINT, MenuItems.RUN_FROM, MenuItems.RUN_ONLY)

@Composable
private fun NodeEditDialog(
    edit: NodeEditUi,
    advanced: Boolean,
    onAction: (FlowEditorAction) -> Unit,
) {
    Dialog(
        onDismissRequest = { onAction(FlowEditorAction.CancelNode) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                SubPageTopBar(
                    title = nodeTypeLabel(edit.type),
                    onBack = { onAction(FlowEditorAction.CancelNode) },
                    actions = {
                        AutopTonalButton(
                            text = stringResource(R.string.flow_ok),
                            onClick = { onAction(FlowEditorAction.ApplyNode) },
                        )
                    },
                )
                edit.error?.let {
                    StatusBanner(
                        tone = StatusTone.BLOCKED,
                        message = it,
                        modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
                    )
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    CompositionLocalProvider(
                        LocalTargetReselect provides { onPicked ->
                            onAction(FlowEditorAction.ReselectTarget(onPicked))
                        },
                    ) {
                        NodeForm(type = edit.type, draft = edit.draft, advanced = advanced, onChange = {
                            onAction(FlowEditorAction.ChangeDraft(it))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowSettingsDialog(
    form: FlowSettingsUi,
    onAction: (FlowEditorAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = { onAction(FlowEditorAction.DismissSettings) },
        title = { Text(stringResource(R.string.editor_settings)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                settingsField(R.string.editor_flow_name, form.name, 1) {
                    onAction(FlowEditorAction.ChangeSettings(form.copy(name = it)))
                }
                settingsField(R.string.editor_flow_desc, form.description, 3) {
                    onAction(FlowEditorAction.ChangeSettings(form.copy(description = it)))
                }
                settingsField(R.string.editor_flow_params, form.params, 8) {
                    onAction(FlowEditorAction.ChangeSettings(form.copy(params = it)))
                }
                settingsField(R.string.editor_flow_settings, form.settings, 10) {
                    onAction(FlowEditorAction.ChangeSettings(form.copy(settings = it)))
                }
                if (form.error) {
                    Text(
                        text = stringResource(R.string.editor_json_invalid),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }
        },
        confirmButton = {
            AutopTextButton(
                text = stringResource(R.string.flow_ok),
                onClick = { onAction(FlowEditorAction.ApplySettings) },
            )
        },
        dismissButton = {
            AutopTextButton(text = stringResource(R.string.flow_cancel), onClick = {
                onAction(FlowEditorAction.DismissSettings)
            })
        },
    )
}

@Composable
private fun settingsField(
    label: Int,
    value: String,
    lines: Int,
    onChange: (String) -> Unit,
) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = LocalSpacing.current.md),
    )
    AutopInputBar(
        value = value,
        onValueChange = onChange,
        spec = InputBarSpec(placeholder = stringResource(label), maxLines = lines),
        modifier = Modifier.fillMaxWidth().padding(vertical = LocalSpacing.current.xs),
    )
}

@Composable
private fun RunDialog(
    run: EditorRunUi,
    flow: FlowDefinition?,
    onAction: (FlowEditorAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(FlowEditorAction.DismissRun) },
        title = {
            Text(
                stringResource(
                    when {
                        run.onlyNode != null -> R.string.editor_run_only
                        run.startAt != null -> R.string.editor_run_from
                        else -> R.string.flow_action_run
                    },
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                flow?.let {
                    ParamFields(it.params, run.params) { name, value ->
                        onAction(
                            FlowEditorAction.ChangeRun(
                                run.copy(
                                    params =
                                        run.params + (name to value),
                                ),
                            ),
                        )
                    }
                }
                if (run.startAt != null || run.onlyNode != null) {
                    settingsField(R.string.editor_run_overrides, run.overrides, 4) {
                        onAction(FlowEditorAction.ChangeRun(run.copy(overrides = it)))
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.editor_run_debug),
                    subtitle = stringResource(R.string.editor_run_debug_hint),
                    checked = run.debug,
                    onCheckedChange = { onAction(FlowEditorAction.ChangeRun(run.copy(debug = it))) },
                )
                run.error?.let {
                    Row(
                        modifier = Modifier.padding(top = LocalSpacing.current.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = runError(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            AutopTextButton(
                text = stringResource(R.string.flow_run_start),
                onClick = { onAction(FlowEditorAction.ConfirmRun) },
            )
        },
        dismissButton = {
            AutopTextButton(
                text = stringResource(R.string.flow_cancel),
                onClick = { onAction(FlowEditorAction.DismissRun) },
            )
        },
    )
}

@Composable
private fun runError(code: String): String =
    when (code) {
        FlowEditorViewModel.MISSING_PARAM -> stringResource(R.string.flow_error_missing_param)
        FlowEditorViewModel.BAD_OVERRIDES -> stringResource(R.string.editor_json_invalid)
        else -> stringResource(R.string.flow_error_generic, code)
    }
