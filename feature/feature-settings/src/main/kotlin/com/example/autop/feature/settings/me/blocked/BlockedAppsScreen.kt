/**
 * 屏蔽的应用页（docs/tech/08 §4.5 me/blocked-apps）：应用多选 + 手动添加包名。
 */
package com.example.autop.feature.settings.me.blocked

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.settings.R

/**
 * 屏蔽的应用页（无状态）。
 *
 * @param state 页面状态。
 * @param onBack 返回"我的"页。
 * @param onAction 用户操作回调。
 */
@Composable
public fun BlockedAppsScreen(
    state: BlockedAppsUiState,
    onBack: () -> Unit,
    onAction: (BlockedAppsAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(title = stringResource(R.string.blocked_title), onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.blocked_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = LocalSpacing.current.lg),
                )
                SectionTitle(text = stringResource(R.string.blocked_count, state.blockedCount))
                AutopInputBar(
                    value = state.query,
                    onValueChange = { onAction(BlockedAppsAction.SetQuery(it)) },
                    spec = InputBarSpec(placeholder = stringResource(R.string.blocked_search)),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LocalSpacing.current.lg),
                )
            }
            items(state.apps, key = { it.packageName }) { app ->
                SwitchRow(
                    title = app.label,
                    subtitle = app.packageName,
                    checked = app.blocked,
                    onCheckedChange = { checked ->
                        onAction(BlockedAppsAction.Toggle(app.packageName, checked))
                    },
                )
            }
            item {
                if (state.apps.isEmpty()) {
                    EmptyState(text = stringResource(R.string.blocked_empty))
                }
                ActionRow(
                    title = stringResource(R.string.blocked_add_manual),
                    subtitle = null,
                    onClick = { onAction(BlockedAppsAction.OpenManual) },
                )
            }
        }
    }
    if (state.manualOpen) {
        ManualAddSheet(
            hasError = state.manualError,
            onAction = onAction,
        )
    }
}

/** 手动添加包名弹层（启动项列表外的应用，如输入法、后台服务）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualAddSheet(
    hasError: Boolean,
    onAction: (BlockedAppsAction) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { onAction(BlockedAppsAction.CloseManual) }) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(LocalSpacing.current.lg),
        ) {
            AutopInputBar(
                value = text,
                onValueChange = { text = it },
                spec = InputBarSpec(placeholder = stringResource(R.string.blocked_package_hint)),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasError) {
                Text(
                    text = stringResource(R.string.blocked_package_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AutopButton(
                text = stringResource(R.string.blocked_add_manual),
                onClick = { onAction(BlockedAppsAction.AddManual(text)) },
                modifier = Modifier.padding(top = LocalSpacing.current.sm),
            )
            AutopTextButton(
                text = stringResource(R.string.rules_cancel),
                onClick = { onAction(BlockedAppsAction.CloseManual) },
            )
        }
    }
}
