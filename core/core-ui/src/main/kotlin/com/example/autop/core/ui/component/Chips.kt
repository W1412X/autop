/**
 * Chip 组件（docs/tech/08 §2）：[AutopChip] 选项 Chip（高 34、8dp 圆角）、
 * [AutopOptionRow] 整行选项（AskCard/问题面板，长文本换行）与
 * [StatusChip] 状态徽标（高 24、6dp 圆角、语义色）。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.core.ui.theme.tone

/** 选项 Chip：高 34、8dp 圆角、labelLarge；边框未选中 outlineVariant / 选中 primary。 */
@Composable
public fun AutopChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.height(AutopDimens.chipHeight),
        shape = MaterialTheme.shapes.small,
        leadingIcon =
            icon?.let { iconVector ->
                {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.Sm.scaled()),
                    )
                }
            },
        border =
            BorderStroke(
                width = BorderWidth,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    )
}

/** 状态徽标：高 24、6dp 圆角、横向内边距 8、labelSmall、语义色、前置 Xs 图标。 */
@Composable
public fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val semantic = LocalSemanticColors.current.tone(tone)
    Row(
        modifier =
            modifier
                .height(AutopDimens.statusChipHeight)
                .background(
                    color = semantic.container,
                    shape = RoundedCornerShape(AutopDimens.statusChipCornerRadius),
                ).padding(horizontal = LocalSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon ?: statusToneIcon(tone),
            contentDescription = null,
            modifier = Modifier.size(IconSize.Xs.scaled()),
            tint = semantic.foreground,
        )
        Spacer(modifier = Modifier.width(LocalSpacing.current.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = semantic.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 整行选项（AskCard/问题面板，docs/tech/08 §4.2）：全宽、chip 同风格描边，
 * 选项独占一行，长文本自动换行不截断（[AutopChip] maxLines=1 不适合长选项）。
 */
@Composable
public fun AutopOptionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    width = BorderWidth,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.small,
                ).clickable(onClick = onClick)
                .padding(horizontal = LocalSpacing.current.md, vertical = LocalSpacing.current.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 状态默认图标（有文字同时有图标，状态不只靠颜色区分）；[StatusBanner] 复用。 */
internal fun statusToneIcon(tone: StatusTone): ImageVector =
    when (tone) {
        StatusTone.RUNNING -> AutopIcons.Play
        StatusTone.WAITING -> AutopIcons.Schedule
        StatusTone.PAUSED -> AutopIcons.Pause
        StatusTone.BLOCKED -> AutopIcons.Block
        StatusTone.SUCCEEDED -> AutopIcons.CheckCircle
        StatusTone.NEUTRAL -> AutopIcons.RadioButtonUnchecked
    }

private val BorderWidth = 1.dp
