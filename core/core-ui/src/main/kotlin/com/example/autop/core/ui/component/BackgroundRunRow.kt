package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/** 输入框尾部的后台运行图标开关；选中态使用主题色容器，保留 Switch 语义。 */
@Composable
public fun BackgroundRunIconButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.core_ui_background_run_title)
    val stateText =
        stringResource(
            if (checked) R.string.core_ui_background_run_state_on else R.string.core_ui_background_run_state_off,
        )
    val containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor =
        if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .size(AutopDimens.compactIconButtonSize)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).semantics(mergeDescendants = true) {
                    contentDescription = title
                    stateDescription = stateText
                },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(AutopDimens.virtualDisplayBadgeSize),
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AutopIcons.VirtualDisplay,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.Sm.scaled()),
                )
            }
        }
    }
}

/** 聊天与悬浮球共用的后台运行开关；不可用原因直接显示在开关上方。 */
@Composable
public fun BackgroundRunRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    unavailableReason: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = AutopIcons.VirtualDisplay,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint =
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.core_ui_background_run_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    unavailableReason
                        ?: stringResource(R.string.core_ui_background_run_desc),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (unavailableReason != null) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}
