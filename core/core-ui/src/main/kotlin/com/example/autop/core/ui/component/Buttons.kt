/**
 * 按钮组件（docs/tech/08 §2）：高 44、横向内边距 16、8dp 圆角、前置图标 Sm、
 * 图标与文字间距 6、单行省略。参数只接收数据与回调。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.scaled

/** 实心主按钮。 */
@Composable
public fun AutopButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(AutopDimens.buttonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = AutopDimens.buttonPaddingH),
    ) {
        AutopButtonContent(text = text, icon = icon)
    }
}

/** 轻填充按钮（次级动作）。 */
@Composable
public fun AutopTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(AutopDimens.buttonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        contentPadding = PaddingValues(horizontal = AutopDimens.buttonPaddingH),
    ) {
        AutopButtonContent(text = text, icon = icon)
    }
}

/** 描边按钮。 */
@Composable
public fun AutopOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(AutopDimens.buttonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = AutopDimens.buttonPaddingH),
    ) {
        AutopButtonContent(text = text, icon = icon)
    }
}

/** 文字按钮；[destructive] 时文字用 error 色。 */
@Composable
public fun AutopTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors =
        if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        }
    TextButton(
        onClick = onClick,
        modifier = modifier.height(AutopDimens.buttonHeight),
        shape = MaterialTheme.shapes.small,
        colors = colors,
        contentPadding = PaddingValues(horizontal = AutopDimens.buttonPaddingH),
    ) {
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AutopButtonContent(
    text: String,
    icon: ImageVector?,
) {
    Row {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconSize.Sm.scaled()),
            )
            Spacer(modifier = Modifier.width(AutopDimens.buttonIconGap))
        }
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
