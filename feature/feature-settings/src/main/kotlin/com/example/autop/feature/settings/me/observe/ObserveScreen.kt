/**
 * 观测方式页（docs/design/observation-modes-coordinate-actions §2.3、§7.1）：
 * 三种模式的启用开关与推荐顺序（越靠上越优先）。
 */
package com.example.autop.feature.settings.me.observe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.feature.settings.R

/**
 * 观测方式页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回。
 * @param onAction 用户操作回调。
 */
@Composable
public fun ObserveScreen(
    state: ObserveUiState,
    onBack: () -> Unit,
    onAction: (ObserveAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.observe_title), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SectionTitle(text = stringResource(R.string.observe_section_priority)) }
            item {
                Text(
                    text = stringResource(R.string.observe_priority_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(state.items) { index, item ->
                ObserveModeRow(
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.items.lastIndex,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun ObserveModeRow(
    item: ObserveModeItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: (ObserveAction) -> Unit,
) {
    Column {
        SwitchRow(
            title = modeTitle(item.id),
            subtitle = modeDescription(item.id),
            checked = item.enabled,
            onCheckedChange = { onAction(ObserveAction.SetEnabled(item.id, it)) },
        )
        Text(
            text = statusText(item),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.id == MODE_LLM && item.unavailableReason != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AutopTextButton(
                    text = stringResource(R.string.observe_action_model_settings),
                    onClick = { onAction(ObserveAction.OpenModelSettings) },
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (canMoveUp) {
                AutopTextButton(
                    text = stringResource(R.string.observe_move_up),
                    onClick = { onAction(ObserveAction.MoveUp(item.id)) },
                )
            }
            if (canMoveDown) {
                AutopTextButton(
                    text = stringResource(R.string.observe_move_down),
                    onClick = { onAction(ObserveAction.MoveDown(item.id)) },
                )
            }
        }
    }
}

/** 可用状态：不可用时直接给出原因。 */
@Composable
private fun statusText(item: ObserveModeItem): String =
    item.unavailableReason
        ?: stringResource(R.string.observe_status_available)

@Composable
private fun modeTitle(modeId: String): String =
    when (modeId) {
        "a11y" -> stringResource(R.string.observe_mode_a11y)
        "visual" -> stringResource(R.string.observe_mode_visual)
        "llm" -> stringResource(R.string.observe_mode_llm)
        else -> modeId
    }

@Composable
private fun modeDescription(modeId: String): String =
    when (modeId) {
        "a11y" -> stringResource(R.string.observe_mode_a11y_desc)
        "visual" -> stringResource(R.string.observe_mode_visual_desc)
        "llm" -> stringResource(R.string.observe_mode_llm_desc)
        else -> ""
    }

/** llm 模式 id（不可用时给出跳转模型配置的入口）。 */
private const val MODE_LLM: String = "llm"
