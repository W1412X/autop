/**
 * 触发方式编辑区（docs/tech/08 §4.3）：手动 / 定时 / 条件 + 过滤条件编辑器。
 */
package com.example.autop.feature.tasks.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.tasks.R
import com.example.autop.feature.tasks.formatClock
import com.example.autop.feature.tasks.formatDate
import com.example.autop.feature.tasks.sourceLabel
import com.example.autop.task.FilterOp
import java.time.LocalTime

/** 触发编辑区。 */
@Composable
internal fun TriggerForm(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.task_edit_section_trigger))
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.screenEdge)) {
        EditTriggerKind.entries.forEach { kind ->
            AutopChip(
                text = stringResource(triggerKindRes(kind)),
                selected = state.triggerKind == kind,
                onClick = { onAction(TaskEditAction.SetTriggerKind(kind)) },
            )
        }
    }
    when (state.triggerKind) {
        EditTriggerKind.MANUAL -> Unit
        EditTriggerKind.SCHEDULE -> ScheduleForm(state, onAction)
        EditTriggerKind.CONDITION -> ConditionForm(state, onAction)
    }
}

@Composable
private fun ScheduleForm(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    var timePicker by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    EditScheduleMode.entries.forEach { mode ->
        OptionRow(
            title = stringResource(scheduleModeRes(mode)),
            subtitle = null,
            value = if (state.scheduleMode == mode) stringResource(R.string.task_edit_selected) else "",
            onClick = { onAction(TaskEditAction.SetScheduleMode(mode)) },
        )
    }
    if (state.scheduleMode != EditScheduleMode.CRON) {
        if (state.scheduleMode == EditScheduleMode.ONCE) {
            OptionRow(
                title = stringResource(R.string.task_edit_date),
                subtitle = formatDate(state.date),
                value = "",
                onClick = { datePicker = true },
            )
        }
        OptionRow(
            title = stringResource(R.string.task_edit_time),
            subtitle = formatClock(state.timeHour, state.timeMinute, state.timeSecond),
            value = "",
            onClick = { timePicker = true },
        )
    } else {
        AutopInputBar(
            value = state.cronExpr,
            onValueChange = { onAction(TaskEditAction.SetCron(it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.task_edit_cron)),
            modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
        )
    }
    if (timePicker) {
        TimeWheelDialog(
            initial = LocalTime.of(state.timeHour, state.timeMinute, state.timeSecond),
            onConfirm = { picked ->
                timePicker = false
                onAction(TaskEditAction.SetTime(picked.hour, picked.minute, picked.second))
            },
            onDismiss = { timePicker = false },
        )
    }
    if (datePicker) {
        DateDialog(
            initial = state.date,
            onConfirm = { picked ->
                datePicker = false
                onAction(TaskEditAction.SetDate(picked))
            },
            onDismiss = { datePicker = false },
        )
    }
}

@Composable
private fun ConditionForm(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    val source = state.sourceOptions.firstOrNull { it.id == state.sourceId }
    SourceRow(state = state, onAction = onAction)
    when {
        source == null -> Unit
        source.fields.isEmpty() -> NoFieldsHint()
        else -> FilterEditor(state = state, source = source, onAction = onAction)
    }
    LlmConditionInput(state = state, onAction = onAction)
    NumberRow(
        label = stringResource(R.string.task_edit_cooldown),
        value = state.cooldownMinutes,
        onChange = { onAction(TaskEditAction.SetCooldown(it)) },
        min = MIN_COOLDOWN_MINUTES,
    )
    NumberRow(
        label = stringResource(R.string.task_edit_daily_limit),
        value = state.dailyLimit,
        onChange = { onAction(TaskEditAction.SetDailyLimit(it)) },
        min = MIN_DAILY_LIMIT,
    )
}

/** 事件源行：点开选择器（中文名 + 可过滤字段 + 权限说明）。 */
@Composable
private fun SourceRow(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    var sourcePicker by remember { mutableStateOf(false) }
    val source = state.sourceOptions.firstOrNull { it.id == state.sourceId }
    OptionRow(
        title = stringResource(R.string.task_edit_source),
        subtitle = sourceSubtitle(state.sourceId, source),
        value = "",
        onClick = { sourcePicker = true },
    )
    if (sourcePicker) {
        OptionSheet(
            title = stringResource(R.string.task_edit_source),
            options =
                state.sourceOptions.map { option ->
                    OptionItem(
                        id = option.id,
                        label = sourceLabel(option.id),
                        description = sourceDescription(option),
                    )
                },
            selectedId = state.sourceId.ifBlank { null },
            onSelect = { onAction(TaskEditAction.SetSource(it)) },
            onDismiss = { sourcePicker = false },
        )
    }
}

/** 事件源没有可过滤字段（如解锁屏幕）时的提示。 */
@Composable
private fun NoFieldsHint() {
    Text(
        text = stringResource(R.string.task_edit_filter_no_fields),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(LocalSpacing.current.screenEdge, vertical = LocalSpacing.current.xs),
    )
}

/** 规则过滤编辑器：条件行（字段/操作/值）+ 添加 + ALL/ANY 组合方式。 */
@Composable
private fun FilterEditor(
    state: TaskEditUiState,
    source: SourceOptionUi,
    onAction: (TaskEditAction) -> Unit,
) {
    state.clauses.forEachIndexed { index, clause ->
        ClauseRow(
            index = index,
            clause = clause,
            fields = source.fields,
            onChange = { onAction(TaskEditAction.SetClause(index, it)) },
            onDelete = { onAction(TaskEditAction.RemoveClause(index)) },
        )
    }
    OptionRow(
        title = stringResource(R.string.task_edit_filter_add),
        subtitle = null,
        value = "",
        onClick = { onAction(TaskEditAction.AddClause) },
    )
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = LocalSpacing.current.screenEdge)) {
        AutopChip(
            text = stringResource(R.string.task_edit_group_all),
            selected = state.groupAll,
            onClick = { onAction(TaskEditAction.SetGroupAll(true)) },
        )
        AutopChip(
            text = stringResource(R.string.task_edit_group_any),
            selected = !state.groupAll,
            onClick = { onAction(TaskEditAction.SetGroupAll(false)) },
        )
    }
}

/** LLM 条件输入：带标签说明（规则先过，再由模型判一次）。 */
@Composable
private fun LlmConditionInput(
    state: TaskEditUiState,
    onAction: (TaskEditAction) -> Unit,
) {
    Text(
        text = stringResource(R.string.task_edit_llm_condition),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge, vertical = LocalSpacing.current.xs),
    )
    AutopInputBar(
        value = state.llmCondition,
        onValueChange = { onAction(TaskEditAction.SetLlmCondition(it)) },
        spec = InputBarSpec(placeholder = stringResource(R.string.task_edit_llm_hint), maxLines = 3),
        modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
    )
}

/** 一条过滤条件：字段/操作点开选择器，值用输入框（不再让用户盲填字段名）。 */
@Composable
private fun ClauseRow(
    index: Int,
    clause: ClauseUi,
    fields: List<String>,
    onChange: (ClauseUi) -> Unit,
    onDelete: () -> Unit,
) {
    var fieldPicker by remember(index) { mutableStateOf(false) }
    var opPicker by remember(index) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalSpacing.current.screenEdge, vertical = LocalSpacing.current.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptionRow(
                title = stringResource(R.string.task_edit_filter_field),
                subtitle = clause.field.ifBlank { stringResource(R.string.task_edit_filter_field_hint) },
                value = "",
                onClick = { fieldPicker = true },
                modifier = Modifier.weight(1f),
            )
            OptionRow(
                title = stringResource(R.string.task_edit_filter_op),
                subtitle = opLabel(clause.op),
                value = "",
                onClick = { opPicker = true },
                modifier = Modifier.weight(1f),
            )
            AutopIconButton(
                icon = AutopIcons.Close,
                contentDescription = stringResource(R.string.task_edit_filter_delete),
                onClick = onDelete,
            )
        }
        AutopInputBar(
            value = clause.value,
            onValueChange = { onChange(clause.copy(value = it)) },
            spec = InputBarSpec(placeholder = stringResource(R.string.task_edit_filter_value)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (fieldPicker) {
        OptionSheet(
            title = stringResource(R.string.task_edit_filter_field),
            options = fields.map { field -> OptionItem(id = field, label = field) },
            selectedId = clause.field.ifBlank { null },
            onSelect = { onChange(clause.copy(field = it)) },
            onDismiss = { fieldPicker = false },
        )
    }
    if (opPicker) {
        OptionSheet(
            title = stringResource(R.string.task_edit_filter_op),
            options = FilterOp.entries.map { op -> OptionItem(id = op.name, label = opLabel(op.name)) },
            selectedId = clause.op,
            onSelect = { onChange(clause.copy(op = it)) },
            onDismiss = { opPicker = false },
        )
    }
}

/** 数值加减行（优先级/冷却期/每日上限，N189）：减号用 `Remove` 图标，label bodyMedium、值定宽居中。 */
@Composable
internal fun NumberRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    min: Int = 0,
    subtitle: String? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AutopDimens.minTouchTarget)
                .padding(horizontal = spacing.screenEdge, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AutopIconButton(
            icon = AutopIcons.Remove,
            contentDescription = stringResource(R.string.task_edit_decrease),
            onClick = { if (value > min) onChange(value - 1) },
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(VALUE_TEXT_WIDTH),
        )
        AutopIconButton(
            icon = AutopIcons.Add,
            contentDescription = stringResource(R.string.task_edit_increase),
            onClick = { onChange(value + 1) },
        )
    }
}

private fun triggerKindRes(kind: EditTriggerKind): Int =
    when (kind) {
        EditTriggerKind.MANUAL -> R.string.task_edit_trigger_manual
        EditTriggerKind.SCHEDULE -> R.string.task_edit_trigger_schedule
        EditTriggerKind.CONDITION -> R.string.task_edit_trigger_condition
    }

private fun scheduleModeRes(mode: EditScheduleMode): Int =
    when (mode) {
        EditScheduleMode.ONCE -> R.string.task_edit_schedule_once
        EditScheduleMode.DAILY -> R.string.task_edit_schedule_daily
        EditScheduleMode.WORKDAY -> R.string.task_edit_schedule_workday
        EditScheduleMode.CRON -> R.string.task_edit_cron
    }

/** 事件源副标题：未选给引导，选中给中文名（有权限要求的源跟在后面）。 */
@Composable
private fun sourceSubtitle(
    sourceId: String,
    source: SourceOptionUi?,
): String {
    if (sourceId.isBlank()) return stringResource(R.string.task_edit_source_hint)
    val label = sourceLabel(sourceId)
    if (source?.requiresPermission == null) return label
    return "$label · ${stringResource(R.string.task_source_need_permission)}"
}

/** 事件源弹层说明：可过滤字段（超长截断）+ 权限要求。 */
@Composable
private fun sourceDescription(source: SourceOptionUi): String {
    val brief =
        if (source.fields.isEmpty()) {
            stringResource(R.string.task_edit_filter_no_fields)
        } else {
            val picked = source.fields.take(FIELD_BRIEF_COUNT).joinToString("、")
            if (source.fields.size > FIELD_BRIEF_COUNT) "$picked…" else picked
        }
    if (source.requiresPermission == null) return brief
    return "${stringResource(R.string.task_source_need_permission)} · $brief"
}

/** 过滤操作中文名（FilterOp.name → 文案）。 */
@Composable
private fun opLabel(op: String): String =
    when (op) {
        FilterOp.EQUALS.name -> stringResource(R.string.task_op_equals)
        FilterOp.NOT_EQUALS.name -> stringResource(R.string.task_op_not_equals)
        FilterOp.CONTAINS.name -> stringResource(R.string.task_op_contains)
        FilterOp.NOT_CONTAINS.name -> stringResource(R.string.task_op_not_contains)
        FilterOp.STARTS_WITH.name -> stringResource(R.string.task_op_starts_with)
        FilterOp.REGEX.name -> stringResource(R.string.task_op_regex)
        FilterOp.IN.name -> stringResource(R.string.task_op_in)
        FilterOp.EXISTS.name -> stringResource(R.string.task_op_exists)
        else -> op
    }

/** 事件源弹层说明里最多列出的字段数。 */
private const val FIELD_BRIEF_COUNT: Int = 4

/** 数值行的值文本宽度（定宽防抖动）。 */
private val VALUE_TEXT_WIDTH = 32.dp
