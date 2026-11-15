/**
 * 图标按钮与图标动作块（docs/tech/08 §2）。
 *
 * 图标着色回落 [LocalContentColor]（LimeTV 修过的深色模式黑图标问题）：
 * 需要自定义色时用 `CompositionLocalProvider(LocalContentColor provides …)` 包裹。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/**
 * 图标按钮。默认 44dp 方形、Md 图标；[compact] 为 32dp、Sm 图标，用于输入区等需要紧凑的位置。
 * [selected] 时实心 8dp 圆角底。
 */
@Composable
public fun AutopIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    compact: Boolean = false,
) {
    val size = Modifier.size(if (compact) CompactButtonSize else AutopDimens.iconButtonSize)
    val iconSize = if (compact) IconSize.Sm.scaled() else IconSize.Md.scaled()
    val iconTint = LocalContentColor.current
    if (selected) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.then(size),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = iconTint,
            )
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier.then(size)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = iconTint,
            )
        }
    }
}

private val CompactButtonSize = 32.dp

/** 图标在上、文字在下的动作块；底 surfaceVariant α0.55，选中 primaryContainer。 */
@Composable
public fun IconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val spacing = LocalSpacing.current
    val container =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL)
        }
    Column(
        modifier =
            modifier
                .background(color = container, shape = MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Md.scaled()),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
