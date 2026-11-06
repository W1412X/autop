/**
 * 节点操作页（ADR-0015）：总开关 + 被记录为屏蔽节点动作的应用，点按即可恢复。
 */
package com.example.autop.feature.settings.me.nodeaction

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/**
 * 节点操作页（无状态）。
 *
 * @param state 页面状态。
 * @param onBack 返回"我的"页。
 * @param onAction 用户操作回调。
 */
@Composable
public fun NodeActionScreen(
    state: NodeActionUiState,
    onBack: () -> Unit,
    onAction: (NodeActionAction) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.node_action_title), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.node_action_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
                )
                SwitchRow(
                    title = stringResource(R.string.node_action_switch_title),
                    subtitle = stringResource(R.string.node_action_switch_subtitle),
                    checked = state.enabled,
                    onCheckedChange = { onAction(NodeActionAction.SetEnabled(it)) },
                )
                SectionTitle(text = stringResource(R.string.node_action_blocked_count, state.blocked.size))
            }
            items(state.blocked, key = { it.packageName }) { row ->
                val time =
                    DateUtils.formatDateTime(
                        context,
                        row.blockedAt,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                    )
                ActionRow(
                    title = row.label,
                    subtitle = stringResource(R.string.node_action_row_subtitle, row.packageName, time),
                    onClick = { onAction(NodeActionAction.Restore(row.packageName)) },
                )
            }
            item {
                if (state.blocked.isEmpty()) {
                    EmptyState(text = stringResource(R.string.node_action_empty))
                }
            }
        }
    }
}
