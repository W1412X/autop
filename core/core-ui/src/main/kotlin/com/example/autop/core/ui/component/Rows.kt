/**
 * 设置页行组件（docs/tech/08 §2）：SwitchRow / OptionRow / InfoRow / ActionRow / NavRow。
 * 16×6（NavRow 16×10）内边距、bodyMedium 标题、bodySmall/labelSmall 副文本；
 * 可点击行满足最小触控区。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/** 开关行：整行可点切换。 */
@Composable
public fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        modifier = modifier.clickable { onCheckedChange(!checked) },
        title = title,
        subtitle = subtitle,
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 选项行：显示当前值，点击由调用方打开 [OptionSheet]。 */
@Composable
public fun OptionRow(
    title: String,
    subtitle: String?,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        modifier = modifier.clickable(onClick = onClick),
        title = title,
        subtitle = subtitle,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChevronSpacer()
        ChevronIcon()
    }
}

/** 信息行：只读，右侧显示值。 */
@Composable
public fun InfoRow(
    title: String,
    subtitle: String?,
    value: String,
    modifier: Modifier = Modifier,
) {
    SettingRow(modifier = modifier, title = title, subtitle = subtitle) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 动作行：整行点击触发动作；[destructive] 时标题用 error 色。 */
@Composable
public fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val titleColor =
        if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    SettingRow(
        modifier = modifier.clickable(onClick = onClick),
        title = title,
        subtitle = subtitle,
        titleColor = titleColor,
    )
}

/** 子页入口行：标题 + 副标题 + 右侧 chevron。 */
@Composable
public fun NavRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        modifier =
            modifier
                .clickable(onClick = onClick)
                .padding(vertical = LocalSpacing.current.xs),
        title = title,
        subtitle = subtitle,
    ) {
        ChevronIcon()
    }
}

@Composable
private fun SettingRow(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AutopDimens.minTouchTarget)
                .padding(horizontal = spacing.lg, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ChevronSpacer() {
    Spacer(modifier = Modifier.width(LocalSpacing.current.xs))
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = AutopIcons.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(IconSize.Sm.scaled()),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
