/**
 * 流程版本历史页：列出历史版本（版本号、时间、reason、当前标记），
 * 支持查看版本定义（只读节点列表）与回滚到指定版本。
 */
@file:Suppress("LongMethod") // Compose 页面：布局声明

package com.example.autop.feature.flows.versions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.flows.R
import com.example.autop.feature.flows.nodeDetail
import com.example.autop.feature.flows.nodeType
import com.example.autop.feature.flows.nodeTypeLabel
import com.example.autop.flow.FlowTree
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 版本历史页。 */
@Composable
public fun FlowVersionsScreen(
    state: FlowVersionsUiState,
    onAction: (FlowVersionsAction) -> Unit,
    onRefresh: () -> Unit,
) {
    // 只读快照页：回页即刷（development-practice-specification）。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) onRefresh()
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var menuVersion by remember { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SubPageTopBar(title = stringResource(R.string.versions_title), onBack = { onAction(FlowVersionsAction.Back) })
        state.restoredVersion?.let { v ->
            StatusBanner(
                tone = StatusTone.SUCCEEDED,
                message = stringResource(R.string.versions_restore_done, v),
                modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
            )
        }
        state.error?.let { msg ->
            StatusBanner(
                tone = StatusTone.BLOCKED,
                message = msg,
                modifier = Modifier.fillMaxWidth().padding(LocalSpacing.current.screenEdge),
            )
        }
        if (state.loaded && state.versions.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.versions_empty),
                icon = AutopIcons.Schedule,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.versions, key = { it.version }) { v ->
                    ListRow(
                        title =
                            if (v.isCurrent) {
                                stringResource(R.string.versions_row_current, v.version)
                            } else {
                                stringResource(R.string.versions_row_title, v.version)
                            },
                        subtitle =
                            subtitleParts(
                                formatTime(v.createdAt),
                                v.reason ?: stringResource(R.string.versions_no_reason),
                            ),
                        leading = AutopIcons.Schedule,
                        modifier = Modifier.clickable { menuVersion = v.version },
                    )
                }
            }
        }
    }
    menuVersion?.let { version ->
        OptionSheet(
            title = stringResource(R.string.versions_row_title, version),
            options =
                listOf(
                    OptionItem(
                        OPT_VIEW,
                        stringResource(R.string.versions_view),
                        icon = OptionIcon.VIEW,
                    ),
                    OptionItem(
                        OPT_RESTORE,
                        stringResource(R.string.versions_restore),
                        icon = OptionIcon.RESTORE,
                    ),
                ),
            selectedId = null,
            onSelect = { selected ->
                menuVersion = null
                when (selected) {
                    OPT_VIEW -> onAction(FlowVersionsAction.View(version))
                    OPT_RESTORE -> onAction(FlowVersionsAction.RequestRestore(version))
                }
            },
            onDismiss = { menuVersion = null },
        )
    }
    VersionDialogs(state = state, onAction = onAction)
}

@Composable
private fun VersionDialogs(
    state: FlowVersionsUiState,
    onAction: (FlowVersionsAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { onAction(FlowVersionsAction.DismissPreview) },
            title = { Text(stringResource(R.string.versions_preview_title, preview.version)) },
            text = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = PREVIEW_MAX_HEIGHT.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (preview.definition.steps.isEmpty()) {
                        Text(stringResource(R.string.versions_preview_empty))
                    } else {
                        FlowTree.allNodes(preview.definition).forEach { node ->
                            Text(
                                text =
                                    nodeTypeLabel(nodeType(node)) +
                                        nodeDetail(node).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = spacing.xs),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                AutopTextButton(
                    text = stringResource(R.string.flow_ok),
                    onClick = { onAction(FlowVersionsAction.DismissPreview) },
                )
            },
        )
    }
    state.pendingRestore?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.versions_restore_title, target.version),
            text = stringResource(R.string.versions_restore_text),
            onConfirm = { onAction(FlowVersionsAction.ConfirmRestore) },
            onDismiss = { onAction(FlowVersionsAction.DismissRestore) },
            destructive = true,
        )
    }
}

private const val OPT_VIEW = "view"
private const val OPT_RESTORE = "restore"
private const val TIME_PATTERN = "MM-dd HH:mm"

/** 预览对话框最大高度（dp）。 */
private const val PREVIEW_MAX_HEIGHT = 400

/** 时间展示（MM-dd HH:mm）。 */
private fun formatTime(millis: Long): String = SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(millis))
