/**
 * 状态块（docs/tech/08 §2）：[EmptyState] / [ErrorState] / [LoadingState]。
 * 内边距 32、图标 48dp onSurfaceVariant、图文间距 12、正文 bodyMedium 居中；
 * 错误态带“重试”。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.scaled

/** 空状态。 */
@Composable
public fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    StateBlock(modifier = modifier, icon = icon ?: AutopIcons.Info, text = text)
}

/** 错误状态（带“重试”）。 */
@Composable
public fun ErrorState(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StateBlock(modifier = modifier, icon = AutopIcons.Error, text = text) {
        AutopTextButton(text = stringResource(R.string.core_ui_retry), onClick = onRetry)
    }
}

/** 加载状态。 */
@Composable
public fun LoadingState(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.core_ui_loading),
) {
    Column(
        modifier = modifier.padding(AutopDimens.statePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AutopDimens.stateGap),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(AutopDimens.loadingIndicatorSize))
        StateText(text = text)
    }
}

@Composable
private fun StateBlock(
    modifier: Modifier,
    icon: ImageVector,
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(AutopDimens.statePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AutopDimens.stateGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Xl.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StateText(text = text)
        action?.invoke()
    }
}

@Composable
private fun StateText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
