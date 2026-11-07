/**
 * 对话列表页（docs/tech/08 §4.1）：无障碍提示 + 运行中卡片 + 分页对话列表，
 * 长按行重命名 / 删除。无状态 Composable，状态与操作走 [ChatListUiState] /
 * [ChatListAction]。
 */
package com.example.autop.feature.chat.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.example.autop.agent.RunOutcome
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.PagedList
import com.example.autop.core.ui.component.PagedListState
import com.example.autop.core.ui.component.TopLevelHeader
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

/**
 * 对话列表页。
 *
 * @param state 页面状态。
 * @param onAction 用户操作回调。
 */
@Composable
public fun ChatListScreen(
    state: ChatListUiState,
    onAction: (ChatListAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        TopLevelHeader(
            title = stringResource(R.string.chat_title),
            actions = {
                NewChatButton(
                    enabled = state.accessibilityConnected,
                    onClick = { onAction(ChatListAction.NewChat) },
                )
            },
        )
        AccessibilityNotice(
            connected = state.accessibilityConnected,
            restrictedSettings = state.restrictedSettings,
            onEnable = { onAction(ChatListAction.EnableAccessibility) },
            shellHint = state.accessibilityConnected && !state.shellConnected,
        )
        state.active?.let { model ->
            ActiveRunCard(
                model = model,
                onStop = { onAction(ChatListAction.StopActive) },
                modifier =
                    Modifier
                        .padding(horizontal = LocalSpacing.current.screenEdge)
                        .padding(bottom = LocalSpacing.current.md),
            )
        }
        ThreadList(state = state, onAction = onAction)
    }
    Sheets(state = state, onAction = onAction)
}

@Composable
private fun NewChatButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val modifier =
        if (enabled) {
            Modifier
        } else {
            Modifier.alphaDisabled()
        }
    AutopIconButton(
        icon = AutopIcons.Add,
        contentDescription = stringResource(R.string.chat_new),
        onClick = { if (enabled) onClick() },
        modifier = modifier,
    )
}

@Composable
private fun ThreadList(
    state: ChatListUiState,
    onAction: (ChatListAction) -> Unit,
) {
    if (state.threads.isEmpty() && state.paging.endReached && !state.paging.loading) {
        EmptyState(
            text = stringResource(R.string.chat_empty),
            icon = AutopIcons.ChatBubbleOutline,
        )
        return
    }
    PagedList(
        items = state.threads,
        state =
            PagedListState(
                loading = state.paging.loading,
                endReached = state.paging.endReached,
                error = if (state.paging.failed) stringResource(R.string.chat_load_failed) else null,
            ),
        onLoadMore = { onAction(ChatListAction.LoadMore) },
        modifier = Modifier.fillMaxSize(),
    ) { row ->
        ThreadRow(row = row, onAction = onAction)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    row: ChatThreadRow,
    onAction: (ChatListAction) -> Unit,
) {
    ListRow(
        title = row.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_untitled),
        subtitle =
            subtitleParts(
                row.status?.let { statusText(it) },
                relativeAgeText(row.updatedAt),
            ),
        leading = statusIcon(row.status),
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onAction(ChatListAction.OpenThread(row.threadId)) },
                    onLongClick = { onAction(ChatListAction.ShowThreadMenu(row.threadId)) },
                ),
    )
}

@Composable
private fun Sheets(
    state: ChatListUiState,
    onAction: (ChatListAction) -> Unit,
) {
    val menuId = state.sheet.menuThreadId
    if (menuId != null) {
        OptionSheet(
            title = null,
            options =
                listOf(
                    OptionItem(id = "rename", label = stringResource(R.string.chat_menu_rename)),
                    OptionItem(id = "delete", label = stringResource(R.string.chat_menu_delete)),
                ),
            selectedId = null,
            onSelect = { id ->
                when (id) {
                    "rename" -> onAction(ChatListAction.ShowRename(menuId))
                    else -> onAction(ChatListAction.ShowDelete(menuId))
                }
                onAction(ChatListAction.DismissMenu)
            },
            onDismiss = { onAction(ChatListAction.DismissMenu) },
        )
    }
    val renameId = state.sheet.renameThreadId
    if (renameId != null) {
        RenameDialog(
            initialTitle = state.threads.firstOrNull { it.threadId == renameId }?.title ?: "",
            onConfirm = { title -> onAction(ChatListAction.Rename(renameId, title)) },
            onDismiss = { onAction(ChatListAction.DismissRename) },
        )
    }
    val deleteId = state.sheet.deleteThreadId
    if (deleteId != null) {
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_title),
            text = stringResource(R.string.chat_delete_body),
            onConfirm = { onAction(ChatListAction.ConfirmDelete) },
            onDismiss = { onAction(ChatListAction.DismissDelete) },
            destructive = true,
        )
    }
}

@Composable
private fun statusText(status: RunOutcome): String =
    stringResource(
        when (status) {
            RunOutcome.SUCCEEDED -> R.string.chat_outcome_succeeded
            RunOutcome.FAILED -> R.string.chat_outcome_failed
            RunOutcome.CANCELLED -> R.string.chat_outcome_cancelled
            RunOutcome.BUDGET_EXHAUSTED -> R.string.chat_outcome_budget
            RunOutcome.INTERRUPTED -> R.string.chat_outcome_interrupted
        },
    )

private fun statusIcon(status: RunOutcome?): ImageVector =
    when (status) {
        null -> AutopIcons.ChatBubbleOutline
        RunOutcome.SUCCEEDED -> AutopIcons.CheckCircle
        RunOutcome.FAILED -> AutopIcons.Error
        RunOutcome.CANCELLED -> AutopIcons.Close
        RunOutcome.BUDGET_EXHAUSTED -> AutopIcons.Warning
        RunOutcome.INTERRUPTED -> AutopIcons.Pause
    }

@Composable
private fun relativeAgeText(updatedAt: Long): String {
    val now = remember(updatedAt) { System.currentTimeMillis() }
    val age = ageOf(updatedAt, now)
    return when (age.unit) {
        AgeUnit.JUST_NOW -> stringResource(R.string.chat_age_now)
        AgeUnit.MINUTES -> stringResource(R.string.chat_age_minutes, age.count)
        AgeUnit.HOURS -> stringResource(R.string.chat_age_hours, age.count)
        AgeUnit.DAYS -> stringResource(R.string.chat_age_days, age.count)
    }
}

private fun Modifier.alphaDisabled(): Modifier = this.alpha(AutopAlpha.DISABLED_CONTENT)

/** 重命名输入框（core-ui 无输入对话框，这里自绘一个）：单行输入 + 取消/确定。 */
@Composable
private fun RenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(initialTitle) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.padding(LocalSpacing.current.md)) {
                Text(
                    text = stringResource(R.string.chat_rename_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                AutopInputBar(
                    value = draft,
                    onValueChange = { draft = it },
                    spec = InputBarSpec(placeholder = stringResource(R.string.chat_rename_hint)),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = LocalSpacing.current.sm),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = LocalSpacing.current.sm),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AutopTextButton(text = stringResource(R.string.chat_cancel), onClick = onDismiss)
                    AutopButton(text = stringResource(R.string.chat_ok), onClick = { onConfirm(draft) })
                }
            }
        }
    }
}
