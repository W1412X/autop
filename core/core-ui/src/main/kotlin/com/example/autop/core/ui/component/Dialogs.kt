/**
 * 对话框与底部弹层（docs/tech/08 §2）：[ConfirmDialog] 确认对话框；
 * [OptionSheet] 选项弹层（skipPartiallyExpanded）。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

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

/** 选项弹层：静默列表行（图标块 / 尾标签可选），选中行浅洗底 + 主题色文字 + 18dp 勾；可滚动（模型列表可达上百项）。 */
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
            verticalArrangement = Arrangement.spacedBy(AutopDimens.optionRowGap),
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
    val palette = rowPalette(tone = item.tone, selected = selected)
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm)
                .clip(shape)
                .background(
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = AutopAlpha.SELECTION_WASH)
                        } else {
                            Color.Transparent
                        },
                    shape = shape,
                ).clickable(onClick = onClick)
                .heightIn(min = AutopDimens.minTouchTarget)
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = item.icon
        if (icon != null) {
            OptionRowIcon(icon = icon, palette = palette, shape = shape)
            Spacer(modifier = Modifier.width(spacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            OptionRowText(item = item, titleColor = palette.title)
        }
        val tag = item.tag
        if (tag != null) {
            Spacer(modifier = Modifier.width(spacing.xs))
            AutopTag(text = tag, emphasized = selected)
        }
        if (selected) {
            Spacer(modifier = Modifier.width(spacing.xs))
            Icon(
                imageVector = AutopIcons.Check,
                contentDescription = stringResource(R.string.core_ui_cd_selected),
                modifier = Modifier.size(AutopDimens.optionCheckSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 选项行的标题与说明（父级负责占满剩余宽度）。 */
@Composable
private fun OptionRowText(
    item: OptionItem,
    titleColor: Color,
) {
    Text(
        text = item.label,
        style = MaterialTheme.typography.labelLarge,
        color = titleColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    item.description?.let { description ->
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 选项行的文字 / 图标 / 图标块配色。 */
private data class OptionRowPalette(
    val title: Color,
    val icon: Color,
    val tile: Color,
)

@Composable
private fun rowPalette(
    tone: OptionTone,
    selected: Boolean,
): OptionRowPalette {
    val colors = MaterialTheme.colorScheme
    val danger = tone == OptionTone.DESTRUCTIVE
    return OptionRowPalette(
        title =
            when {
                danger -> colors.error
                selected -> colors.primary
                else -> colors.onSurface
            },
        icon =
            when {
                danger -> colors.error
                selected -> colors.primary
                else -> colors.onSurfaceVariant
            },
        tile =
            when {
                danger -> colors.error.copy(alpha = AutopAlpha.DANGER_TILE_FILL)
                selected -> colors.primary.copy(alpha = AutopAlpha.SELECTION_TILE_FILL)
                else -> colors.surfaceContainerHigh
            },
    )
}

@Composable
private fun OptionRowIcon(
    icon: OptionIcon,
    palette: OptionRowPalette,
    shape: Shape,
) {
    Box(
        modifier =
            Modifier
                .size(AutopDimens.optionIconTile)
                .background(color = palette.tile, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.vector(),
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint = palette.icon,
        )
    }
}

/** 选项图标枚举 → 统一图标入口（纯一一映射）。 */
@Suppress("CyclomaticComplexMethod")
private fun OptionIcon.vector(): ImageVector =
    when (this) {
        OptionIcon.EDIT -> AutopIcons.Edit
        OptionIcon.COPY -> AutopIcons.Copy
        OptionIcon.DELETE -> AutopIcons.Delete
        OptionIcon.MOVE -> AutopIcons.OpenWith
        OptionIcon.UP -> AutopIcons.ArrowUp
        OptionIcon.DOWN -> AutopIcons.ArrowDown
        OptionIcon.HISTORY -> AutopIcons.History
        OptionIcon.EXPORT -> AutopIcons.Export
        OptionIcon.ADD -> AutopIcons.Add
        OptionIcon.FOLDER -> AutopIcons.Folder
        OptionIcon.VIEW -> AutopIcons.Visibility
        OptionIcon.RESTORE -> AutopIcons.Refresh
        OptionIcon.RUN -> AutopIcons.Play
        OptionIcon.TOGGLE -> AutopIcons.CheckCircle
        OptionIcon.BREAKPOINT -> AutopIcons.Bug
        OptionIcon.FORK -> AutopIcons.Branch
        OptionIcon.RAW -> AutopIcons.DataObject
        OptionIcon.TEMPLATE -> AutopIcons.Description
        OptionIcon.AI -> AutopIcons.Psychology
        OptionIcon.SAVE_TASK -> AutopIcons.ListAlt
    }
