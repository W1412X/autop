/**
 * 列表行（docs/tech/08 §2）：内边距 16×10、左侧 48dp 图标块（surfaceVariant α0.55，
 * 12dp 圆角，图标 Md）、间距 12、标题 bodyMedium、副标题 bodySmall、可选 3dp 进度条。
 * 行本身不带点击；可点击时由调用方在 modifier 里加 `clickable`。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/** 列表行（无进度条）。 */
@Composable
public fun ListRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListRowContent(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        leading = leading,
        trailing = trailing,
    )
}

/** 带 3dp 进度条的列表行（[progress] 取 0f..1f）。 */
@Composable
public fun ListRow(
    title: String,
    subtitle: String,
    progress: Float,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListRowContent(
            modifier = Modifier,
            title = title,
            subtitle = subtitle,
            leading = leading,
            trailing = null,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(AutopDimens.progressBarHeight),
        )
    }
}

/** 副标题按 “A · B · C” 拼接（空段跳过）。 */
public fun subtitleParts(vararg parts: String?): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun ListRowContent(
    modifier: Modifier,
    title: String,
    subtitle: String,
    leading: ImageVector?,
    trailing: (@Composable () -> Unit)?,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            ListRowIcon(leading)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ListRowIcon(icon: ImageVector) {
    Box(
        modifier =
            Modifier
                .padding(end = LocalSpacing.current.md)
                .size(AutopDimens.listThumb)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Md.scaled()),
        )
    }
}
