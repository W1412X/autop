/**
 * 对话页弹层（docs/tech/08 §4.2）：顶栏"更多"菜单、长按条目菜单、
 * "查看原始数据"全屏 mono JSON（可复制）、删除确认框。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.ConfirmDialog
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

/** 对话页全部弹层（状态来自 [ChatThreadSheet]）。 */
@Composable
internal fun ThreadSheets(
    state: ChatThreadUiState,
    onAction: (ChatThreadAction) -> Unit,
) {
    if (state.sheet.moreMenu) {
        OptionSheet(
            title = null,
            options =
                listOf(
                    OptionItem(id = "save_task", label = stringResource(R.string.thread_menu_save_task)),
                    OptionItem(id = "export", label = stringResource(R.string.thread_menu_export)),
                    OptionItem(id = "delete", label = stringResource(R.string.thread_menu_delete)),
                ),
            selectedId = null,
            onSelect = { id ->
                when (id) {
                    "save_task" -> onAction(ChatThreadAction.SaveAsTask)
                    "export" -> onAction(ChatThreadAction.ExportLog)
                    else -> onAction(ChatThreadAction.DeleteThread)
                }
            },
            onDismiss = { onAction(ChatThreadAction.DismissMoreMenu) },
        )
    }
    EntryMenu(state = state, onAction = onAction)
    state.sheet.rawSeq?.let {
        RawDataDialog(
            json = state.rawJson.orEmpty(),
            onDismiss = { onAction(ChatThreadAction.DismissRaw) },
        )
    }
    if (state.sheet.deleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_title),
            text = stringResource(R.string.chat_delete_body),
            onConfirm = { onAction(ChatThreadAction.ConfirmDelete) },
            onDismiss = { onAction(ChatThreadAction.DismissDelete) },
            destructive = true,
        )
    }
}

/** 长按条目菜单："从这里分叉"（仅可 fork 点）与"查看原始数据"。 */
@Composable
private fun EntryMenu(
    state: ChatThreadUiState,
    onAction: (ChatThreadAction) -> Unit,
) {
    val menuSeq = state.sheet.menuSeq ?: return
    val forkable = state.entries.any { it.seq == menuSeq && it.forkable }
    OptionSheet(
        title = null,
        options =
            buildList {
                if (forkable) {
                    add(OptionItem(id = "fork", label = stringResource(R.string.thread_menu_fork)))
                }
                add(OptionItem(id = "raw", label = stringResource(R.string.thread_menu_raw)))
            },
        selectedId = null,
        onSelect = { id ->
            if (id == "fork") {
                onAction(ChatThreadAction.ForkFrom(menuSeq))
            } else {
                onAction(ChatThreadAction.ShowRaw(menuSeq))
            }
        },
        onDismiss = { onAction(ChatThreadAction.DismissEntryMenu) },
    )
}

/** "查看原始数据"：全屏 mono JSON（SelectionContainer 支持系统复制）。 */
@Composable
private fun RawDataDialog(
    json: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(all = LocalSpacing.current.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.thread_raw_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = LocalSpacing.current.sm),
                    )
                    AutopIconButton(
                        icon = AutopIcons.Close,
                        contentDescription = stringResource(R.string.thread_raw_close),
                        onClick = onDismiss,
                    )
                }
                SelectionContainer {
                    Text(
                        text = json,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(all = LocalSpacing.current.md),
                    )
                }
            }
        }
    }
}
