/**
 * 录制面板的无状态界面：只读 [RecordUiState]，操作通过 [RecordCallbacks] 交给控制器。
 *
 * 默认是一条小提示条（状态、已记录数、最近一步、保存/详情/收起）；点“详情”才展开完整内容，
 * 展开区有最大高度并可滑动，不会遮住被录制的应用。
 */
package com.example.autop.feature.overlay.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.device.UserActionType
import com.example.autop.feature.overlay.R
import com.example.autop.flow.RecordedStep
import com.example.autop.flow.TypedInput

/** 展开详情时内容区最大高度占屏幕高度的比例。 */
private const val DETAILS_MAX_HEIGHT_FRACTION: Float = 0.5f

/** 面板展示状态。 */
internal data class RecordUiState(
    val active: Boolean,
    val actionCount: Int,
    val saving: Boolean,
    val notice: RecordNotice?,
    /** 已拾取、等待选择步骤类型的元素。 */
    val picked: Boolean,
    /** 试运行进行中。 */
    val trialing: Boolean,
    /** 录到的文字输入（可设为参数）。 */
    val inputs: List<TypedInput>,
    /** 已选为参数的输入的节点 id。 */
    val chosen: Set<String>,
    /** AI 整理后的名称。 */
    val polishedName: String?,
    /** 是否展开详情。 */
    val expanded: Boolean,
    /** 最近录到的操作（时间正序）。 */
    val recent: List<RecordedStep>,
)

/** 面板操作回调。 */
internal data class RecordCallbacks(
    val onStart: () -> Unit,
    val onUndo: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onClose: () -> Unit,
    val onToggleDetails: () -> Unit,
    val onPick: () -> Unit,
    val onAddClick: () -> Unit,
    val onAddWait: () -> Unit,
    val onDiscardPick: () -> Unit,
    val onTrialAll: () -> Unit,
    val onTrialLast: () -> Unit,
    val onToggleParam: (String) -> Unit,
    val onPolish: () -> Unit,
)

/** 录制面板内容。 */
@Composable
internal fun RecordScreen(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    val spacing = LocalSpacing.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = spacing.xs,
        shadowElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (state.active) ActiveHeader(state, callbacks) else IdleHeader(callbacks)
            NoticeLine(state.notice)
            if (state.active && state.expanded) Details(state, callbacks)
        }
    }
}

/** 未开始录制：标题、一句话提示、开始/收起。 */
@Composable
private fun IdleHeader(callbacks: RecordCallbacks) {
    val spacing = LocalSpacing.current
    Text(stringResource(R.string.record_title), style = MaterialTheme.typography.titleSmall)
    Text(
        text = stringResource(R.string.record_hint_short),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = callbacks.onStart) { Text(stringResource(R.string.record_start)) }
        TextButton(onClick = callbacks.onClose) { Text(stringResource(R.string.record_close)) }
    }
}

/** 录制中的小提示条：状态 + 已记录数、最近一步、保存/详情/收起。 */
@Composable
private fun ActiveHeader(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Icon(
            imageVector = AutopIcons.Record,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(spacing.lg),
        )
        Text(
            text = stringResource(R.string.record_status_compact, state.actionCount),
            style = MaterialTheme.typography.titleSmall,
        )
    }
    Text(
        text = state.recent.lastOrNull()?.let { stepText(it) } ?: stringResource(R.string.record_hint_short),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = callbacks.onSave, enabled = state.actionCount > 0 && !state.saving) {
            Text(stringResource(R.string.record_save))
        }
        TextButton(onClick = callbacks.onToggleDetails) {
            Text(stringResource(if (state.expanded) R.string.record_details_hide else R.string.record_details))
        }
        TextButton(onClick = callbacks.onClose) { Text(stringResource(R.string.record_close)) }
    }
}

@Composable
private fun NoticeLine(notice: RecordNotice?) {
    if (notice == null) return
    val text =
        when (notice) {
            RecordNotice.SAVED -> R.string.record_saved
            RecordNotice.FAILED -> R.string.record_failed
            RecordNotice.PICK_FAILED -> R.string.record_pick_failed
            RecordNotice.TRIAL_SUCCEEDED -> R.string.record_trial_succeeded
            RecordNotice.TRIAL_FAILED -> R.string.record_trial_failed
            RecordNotice.POLISH_FAILED -> R.string.record_polish_failed
        }
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall)
}

/** 展开的详情：最近操作、参数、整理名称、拾取、试运行、撤销与放弃。内容超出时可滑动。 */
@Composable
private fun Details(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    val spacing = LocalSpacing.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * DETAILS_MAX_HEIGHT_FRACTION).dp
    Column(
        modifier =
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RecentSteps(state.recent)
        ParamChooser(state, callbacks)
        PolishRow(state, callbacks)
        PickRow(state, callbacks)
        TrialAndUndoRow(state, callbacks)
        TextButton(onClick = callbacks.onCancel) { Text(stringResource(R.string.record_cancel)) }
    }
}

@Composable
private fun RecentSteps(recent: List<RecordedStep>) {
    Text(stringResource(R.string.record_recent_title), style = MaterialTheme.typography.labelMedium)
    if (recent.isEmpty()) {
        Text(
            text = stringResource(R.string.record_recent_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    recent.forEach { step ->
        Text(
            text = stepText(step),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ParamChooser(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    if (state.inputs.isEmpty()) return
    Text(stringResource(R.string.record_param_title), style = MaterialTheme.typography.labelMedium)
    state.inputs.forEach { input ->
        val on = input.nodeId in state.chosen
        OutlinedButton(onClick = { callbacks.onToggleParam(input.nodeId) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (on) "✓ ${input.text}" else input.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PolishRow(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    OutlinedButton(
        onClick = callbacks.onPolish,
        enabled = state.actionCount > 0 && !state.saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.record_polish))
    }
    state.polishedName?.let {
        Text(stringResource(R.string.record_polished, it), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PickRow(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    val spacing = LocalSpacing.current
    if (!state.picked) {
        OutlinedButton(onClick = callbacks.onPick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.record_pick))
        }
        return
    }
    Text(stringResource(R.string.record_pick_picked), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Button(onClick = callbacks.onAddClick) { Text(stringResource(R.string.record_pick_click)) }
        OutlinedButton(onClick = callbacks.onAddWait) { Text(stringResource(R.string.record_pick_wait)) }
    }
    TextButton(onClick = callbacks.onDiscardPick) { Text(stringResource(R.string.record_pick_discard)) }
}

@Composable
private fun TrialAndUndoRow(
    state: RecordUiState,
    callbacks: RecordCallbacks,
) {
    val spacing = LocalSpacing.current
    val hasActions = state.actionCount > 0
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        OutlinedButton(onClick = callbacks.onTrialLast, enabled = hasActions && !state.trialing) {
            Text(stringResource(R.string.record_trial_last))
        }
        OutlinedButton(onClick = callbacks.onTrialAll, enabled = hasActions && !state.trialing) {
            Text(stringResource(R.string.record_trial_all))
        }
    }
    OutlinedButton(onClick = callbacks.onUndo, enabled = hasActions) { Text(stringResource(R.string.record_undo)) }
}

/** 一条已录操作的展示文字（有可读目标时带上目标）。 */
@Composable
private fun stepText(step: RecordedStep): String {
    val target = step.target
    val res =
        when (step.type) {
            UserActionType.CLICK -> if (target != null) R.string.record_step_click else R.string.record_step_click_plain
            UserActionType.LONG_CLICK ->
                if (target != null) R.string.record_step_long_click else R.string.record_step_long_click_plain
            UserActionType.TEXT_CHANGED ->
                if (target != null) R.string.record_step_input else R.string.record_step_input_plain
            UserActionType.SCROLL_FORWARD -> R.string.record_step_scroll_forward
            UserActionType.SCROLL_BACKWARD -> R.string.record_step_scroll_backward
            UserActionType.WINDOW_CHANGED -> R.string.record_step_window
            UserActionType.WAIT_ELEMENT ->
                if (target != null) R.string.record_step_wait else R.string.record_step_wait_plain
        }
    return if (target != null) stringResource(res, target) else stringResource(res)
}
