/**
 * 输入栏（docs/tech/08 §2 `AutopInputBar`）：`BasicTextField` 自绘，最小高 44、
 * 8dp 圆角、1dp outlineVariant 描边（聚焦 primary）、横向内边距 12、占位 bodyLarge、
 * 光标 primary；支持多行（[InputBarSpec.maxLines]），尾部插槽放按钮。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AutopDimens

/** 输入栏。 */
@Suppress("LongParameterList") // 头/尾插槽与样式参数不可再减（questions.md N114 口径）
@Composable
public fun AutopInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    spec: InputBarSpec = InputBarSpec(),
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor =
        if (focused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = AutopDimens.inputHeight)
                .border(
                    width = BorderWidth,
                    color = borderColor,
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = AutopDimens.inputPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InputBarLeading(leading = leading)
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = LocalRowPadding),
        ) {
            if (value.isEmpty() && spec.placeholder.isNotEmpty()) {
                Text(
                    text = spec.placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = spec.maxLines,
                visualTransformation =
                    if (spec.password) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
            )
        }
        InputBarTrailing(trailing = trailing)
    }
}

/** 头部插槽（如搜索图标）：与文本留 [TrailingGap] 间隙、上下留 [LocalRowPadding]。 */
@Composable
private fun RowScope.InputBarLeading(leading: (@Composable RowScope.() -> Unit)?) {
    if (leading == null) return
    Row(
        modifier = Modifier.padding(vertical = LocalRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
    }
    Spacer(modifier = Modifier.width(TrailingGap))
}

/** 尾部插槽：与文本留 [TrailingGap] 间隙、上下留 [LocalRowPadding]（按钮不贴边，N172）。 */
@Composable
private fun RowScope.InputBarTrailing(trailing: (@Composable RowScope.() -> Unit)?) {
    if (trailing == null) return
    Spacer(modifier = Modifier.width(TrailingGap))
    Row(
        modifier = Modifier.padding(vertical = LocalRowPadding),
        horizontalArrangement = Arrangement.spacedBy(LocalRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        trailing()
    }
}

private val BorderWidth = 1.dp
private val LocalRowPadding = 4.dp
private val TrailingGap = 8.dp
