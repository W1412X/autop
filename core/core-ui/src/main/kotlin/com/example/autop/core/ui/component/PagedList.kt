/**
 * 分页列表（docs/tech/08 §2）：滚动接近末尾（提前 [AutopDimens.AUTOLOAD_AHEAD] 项）
 * 自动触发 [onLoadMore]；尾部三态：加载中 / 错误（“重试”） / 已到底。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing

/** 分页列表：接近末尾自动加载，尾部行展示加载/错误/到底状态。 */
@Composable
public fun <T> PagedList(
    items: List<T>,
    state: PagedListState,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(items.size, state, listState) {
        derivedStateOf {
            val lastVisible =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            lastVisible >= items.size - AutopDimens.AUTOLOAD_AHEAD &&
                !state.loading &&
                state.error == null &&
                !state.endReached
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LazyColumn(modifier = modifier, state = listState) {
        items(items.size) { index -> itemContent(items[index]) }
        item { PagedFooter(state = state, onRetry = onLoadMore) }
    }
}

@Composable
private fun PagedFooter(
    state: PagedListState,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(AutopDimens.pagedFooterHeight),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.error != null -> FooterError(error = state.error, onRetry = onRetry)
            state.loading ->
                CircularProgressIndicator(
                    modifier = Modifier.size(AutopDimens.loadingIndicatorSize),
                )
            state.endReached ->
                Text(
                    text = stringResource(R.string.core_ui_list_end),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
        }
    }
}

@Composable
private fun FooterError(
    error: String,
    onRetry: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AutopTextButton(text = stringResource(R.string.core_ui_retry), onClick = onRetry)
    }
}
