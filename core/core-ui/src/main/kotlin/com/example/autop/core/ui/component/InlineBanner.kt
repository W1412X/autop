/**
 * 顶部横幅提示（docs/tech/08 §2 `InlineBanner`，替代 Snackbar）：
 * surfaceVariant α0.55 底、12dp 圆角、Sm 图标 + bodySmall 文字，
 * 显示 [durationMs]（`ui.banner.durationMs`）后消失，可手动关闭，放内容顶部。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import kotlinx.coroutines.delay

/** 顶部横幅提示。 */
@Composable
public fun InlineBanner(
    message: String,
    onDismiss: () -> Unit,
    durationMs: Long,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    LaunchedEffect(message, durationMs) {
        delay(durationMs)
        onDismiss()
    }
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ).padding(start = spacing.md, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon ?: AutopIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(AutopDimens.bannerCloseSize),
        ) {
            Icon(
                imageVector = AutopIcons.Close,
                contentDescription = stringResource(R.string.core_ui_close),
                modifier = Modifier.size(IconSize.Sm.scaled()),
            )
        }
    }
}
