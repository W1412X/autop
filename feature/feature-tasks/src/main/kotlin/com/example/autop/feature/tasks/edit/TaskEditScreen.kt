/**
 * 任务编辑页（docs/tech/08 §4.3）：一句话生成草稿 + 基本 / 触发 / 执行 / 试运行表单。
 */
package com.example.autop.feature.tasks.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.tasks.R

/** 任务编辑页。 */
@Composable
public fun TaskEditScreen(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(
            title = stringResource(if (state.isNew) R.string.task_edit_new_title else R.string.task_edit_title),
            onBack = { onAction(TaskEditAction.Back) },
            actions = {
                AutopTonalButton(
                    text = stringResource(R.string.task_edit_save),
                    onClick = { onAction(TaskEditAction.Save) },
                )
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { DraftSection(state = state, onAction = onAction) }
            item { BasicSection(state = state, onAction = onAction) }
            item { ExecutorSection(state = state, onAction = onAction) }
            item { TriggerForm(state = state, onAction = onAction) }
            item { ExecSection(state = state, onAction = onAction) }
            // 试运行只对条件触发有意义（定时/手动没有事件可比对）。
            if (state.triggerKind == EditTriggerKind.CONDITION) {
                item { SimulateSection(state = state, onAction = onAction) }
            }
        }
    }
    state.error?.let { code ->
        StatusBanner(
            tone = StatusTone.BLOCKED,
            message = errorText(code),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.screenEdge),
        )
    }
}

@Composable
private fun DraftSection(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.task_edit_section_desc))
    AutopInputBar(
        value = state.description,
        onValueChange = { onAction(TaskEditAction.SetDescription(it)) },
        spec = InputBarSpec(placeholder = stringResource(R.string.task_edit_desc_hint), maxLines = 2),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge),
    )
    AutopButton(
        text =
            stringResource(
                if (state.generating) R.string.task_edit_generating else R.string.task_edit_generate,
            ),
        onClick = { onAction(TaskEditAction.GenerateDraft) },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge),
    )
}

@Composable
private fun BasicSection(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    SectionTitle(text = stringResource(R.string.task_edit_section_basic))
    AutopInputBar(
        value = state.name,
        onValueChange = { onAction(TaskEditAction.SetName(it)) },
        spec = InputBarSpec(placeholder = stringResource(R.string.task_edit_name)),
        modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
    )
    if (!state.executorFlow) {
        AutopInputBar(
            value = state.goal,
            onValueChange = { onAction(TaskEditAction.SetGoal(it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.task_edit_goal), maxLines = 3),
            modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
        )
        SwitchRow(
            title = stringResource(R.string.task_edit_needs_screen),
            subtitle = stringResource(R.string.task_edit_needs_screen_hint),
            checked = state.needsScreen,
            onCheckedChange = { onAction(TaskEditAction.SetNeedsScreen(it)) },
        )
    }
}

/** 执行方式：智能体 / 流程；选流程后按其参数声明填写参数。 */
@Composable
private fun ExecutorSection(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    var picking by remember { mutableStateOf(false) }
    SectionTitle(text = stringResource(R.string.task_edit_section_executor))
    OptionRow(
        title = stringResource(R.string.task_edit_executor),
        subtitle = stringResource(R.string.task_edit_executor_hint),
        value =
            stringResource(
                if (state.executorFlow) R.string.task_edit_executor_flow else R.string.task_edit_executor_agent,
            ),
        onClick = { onAction(TaskEditAction.SetExecutorFlow(!state.executorFlow)) },
    )
    if (!state.executorFlow) return
    val chosen = state.flowOptions.firstOrNull { it.id == state.flowId }
    OptionRow(
        title = stringResource(R.string.task_edit_flow_pick),
        subtitle = null,
        value = chosen?.name ?: stringResource(R.string.task_edit_flow_none),
        onClick = { picking = true },
    )
    if (chosen != null && chosen.params.isNotEmpty()) {
        SectionTitle(text = stringResource(R.string.task_edit_flow_params))
        chosen.params.forEach { p ->
            AutopInputBar(
                value = state.flowParams[p.name].orEmpty(),
                onValueChange = { onAction(TaskEditAction.SetFlowParam(p.name, it)) },
                spec = InputBarSpec(placeholder = paramHint(p), maxLines = 3),
                modifier = Modifier.fillMaxWidth().padding(spacing.screenEdge),
            )
        }
    }
    if (picking) {
        OptionSheet(
            title = stringResource(R.string.task_edit_flow_pick),
            options = state.flowOptions.map { OptionItem(id = it.id, label = it.name) },
            selectedId = state.flowId.ifBlank { null },
            onSelect = {
                picking = false
                onAction(TaskEditAction.SetFlow(it))
            },
            onDismiss = { picking = false },
        )
    }
}

/** 参数输入框的占位提示：名称 + 说明，必填加标记，有默认值附上。 */
@Composable
private fun paramHint(p: FlowParamUi): String {
    val required = if (p.required) " ${stringResource(R.string.task_edit_flow_param_required)}" else ""
    val note =
        p.description
            .takeIf { it.isNotBlank() }
            ?.let { " · $it" }
            .orEmpty()
    val default =
        p.defaultText
            .takeIf { it.isNotBlank() }
            ?.let { " · $it" }
            .orEmpty()
    return "${p.name}$required$note$default"
}

@Composable
private fun ExecSection(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.task_edit_section_exec))
    OptionRow(
        title = stringResource(R.string.task_edit_locked_behavior),
        subtitle = stringResource(R.string.task_edit_locked_note),
        value =
            stringResource(
                if (state.lockedRemind) R.string.task_edit_locked_remind else R.string.task_edit_locked_skip,
            ),
        onClick = { onAction(TaskEditAction.SetLockedBehavior(!state.lockedRemind)) },
    )
    NumberRow(
        label = stringResource(R.string.task_edit_priority),
        value = state.priority,
        onChange = { onAction(TaskEditAction.SetPriority(it)) },
        subtitle = stringResource(R.string.task_edit_priority_hint),
    )
}

@Composable
private fun SimulateSection(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.task_edit_section_sim))
    AutopButton(
        text = stringResource(R.string.task_edit_sim_run),
        onClick = { onAction(TaskEditAction.OpenSimulate) },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge),
    )
    if (state.simQueried && state.simEvents.isEmpty()) {
        ListRow(title = stringResource(R.string.task_edit_sim_no_events), subtitle = "")
    }
    if (state.simQueried && state.simEvents.isNotEmpty()) {
        state.simEvents.forEachIndexed { index, event ->
            OptionRow(
                title = event,
                subtitle = null,
                value = if (state.simPicked == index) stringResource(R.string.task_edit_selected) else "",
                onClick = { onAction(TaskEditAction.PickSimEvent(index)) },
            )
        }
    }
    state.simResult?.let { result ->
        StatusBanner(
            tone = if (result.matched) StatusTone.SUCCEEDED else StatusTone.NEUTRAL,
            message = stringResource(resultHeadlineRes(result)) + detailLine(result.details),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.screenEdge),
        )
        AutopTonalButton(
            text = stringResource(R.string.task_edit_sim_use),
            onClick = { onAction(TaskEditAction.RunSimOnce) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.screenEdge),
        )
    }
    if (state.simStarted) {
        StatusBanner(
            tone = StatusTone.SUCCEEDED,
            message = stringResource(R.string.task_edit_sim_started),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.screenEdge),
        )
    }
}

/** 试运行明细行：闸门原因码给中文，其余（字段提取 key=value）原样，逐条前置 “ · ”。 */
@Composable
private fun detailLine(details: List<String>): String {
    if (details.isEmpty()) return ""
    val builder = StringBuilder()
    for (detail in details) {
        builder.append(" · ").append(detailText(detail))
    }
    return builder.toString()
}

/** 单条明细文案：闸门原因码给中文，未知码原样。 */
@Composable
private fun detailText(detail: String): String =
    when (detail) {
        "COOLDOWN" -> stringResource(R.string.task_sim_gate_cooldown)
        "DAILY_LIMIT" -> stringResource(R.string.task_sim_gate_limit)
        "DUPLICATE" -> stringResource(R.string.task_sim_gate_duplicate)
        else -> detail
    }

/** 试运行结论：命中→可触发；被闸门拦→带拦截原因；规则不匹配→不会触发。 */
@StringRes
private fun resultHeadlineRes(result: SimResultUi): Int =
    when {
        result.matched -> R.string.task_edit_sim_result_match
        result.details.any { it in GATE_REASONS } -> R.string.task_edit_sim_result_blocked
        else -> R.string.task_edit_sim_result_miss
    }

/** 闸门原因码（FireGate.Verdict.Reason）。 */
private val GATE_REASONS: Set<String> = setOf("COOLDOWN", "DAILY_LIMIT", "DUPLICATE")

@Composable
private fun errorText(code: String): String =
    when (code) {
        TaskEditError.NOT_FOUND.name -> stringResource(R.string.task_detail_not_found)
        TaskEditError.NAME.name -> stringResource(R.string.task_edit_error_name)
        TaskEditError.GOAL.name -> stringResource(R.string.task_edit_error_goal)
        TaskEditError.SOURCE.name -> stringResource(R.string.task_edit_error_source)
        TaskEditError.CRON.name -> stringResource(R.string.task_edit_error_cron)
        TaskEditError.TIME.name -> stringResource(R.string.task_edit_error_time)
        TaskEditError.COOLDOWN.name -> stringResource(R.string.task_edit_error_cooldown)
        TaskEditError.FLOW.name -> stringResource(R.string.task_edit_error_flow)
        TaskEditError.FLOW_PARAM.name -> stringResource(R.string.task_edit_error_flow_param)
        TaskEditError.LIMIT.name -> stringResource(R.string.task_edit_error_limit)
        else -> stringResource(R.string.task_edit_save_failed, code)
    }
