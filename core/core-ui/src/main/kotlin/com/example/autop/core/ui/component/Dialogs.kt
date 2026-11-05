/**
 * 对话框与底部弹层（docs/tech/08 §2）：[ConfirmDialog] 确认对话框；
 * [OptionSheet] 选项弹层（skipPartiallyExpanded）。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing

/** 确认对话框：确认实心按钮、取消文字按钮、正文 onSurfaceVariant；破坏性确认用 error 色。 */
@Composable
public fun ConfirmDialog(
    title: String,
    text: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val confirmColors =
        if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text =
            text?.let { body ->
                {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = confirmColors,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.core_ui_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.core_ui_cancel))
            }
        },
    )
}

/**
 * 只读信息对话框：单“关闭”实心按钮，正文 onSurfaceVariant 可滚动。
 * 用于诊断类详情展示（如测试连接失败原因），内容由调用方组装。
 */
@Composable
public fun InfoDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.core_ui_close))
            }
        },
    )
}

/** 选项弹层：选项行 16×10，选中项文字 primary + 18dp 勾；选项区可滚动（模型列表可达上百项）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun OptionSheet(
    title: String?,
    options: List<OptionItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = LocalSpacing.current.xl),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.padding(
                            start = LocalSpacing.current.lg,
                            end = LocalSpacing.current.lg,
                            top = LocalSpacing.current.sm,
                            bottom = LocalSpacing.current.xs,
                        ),
                )
            }
            options.forEach { item ->
                OptionSheetRow(
                    item = item,
                    selected = item.id == selectedId,
                    onClick = {
                        onSelect(item.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun OptionSheetRow(
    item: OptionItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xs)
                .background(
                    color =
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (selected) AutopAlpha.OPTION_FILL_SELECTED else AutopAlpha.OPTION_FILL,
                        ),
                    shape = MaterialTheme.shapes.small,
                ).clickable(onClick = onClick)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.description != null) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = AutopIcons.Check,
                contentDescription = stringResource(R.string.core_ui_cd_selected),
                modifier = Modifier.size(AutopDimens.optionCheckSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
