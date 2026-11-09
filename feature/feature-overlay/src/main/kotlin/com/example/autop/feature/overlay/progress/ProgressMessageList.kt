/**
 * 进度消息流（进度悬浮窗与顶部运行详情面板共用）：chat 同款消息组件，新行自动滚到底。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.example.autop.core.ui.theme.LocalSpacing

/** 消息流：只读展示（N182 视觉口径），[modifier] 由调用方定尺寸。 */
@Composable
internal fun ProgressMessageList(
    rows: List<ProgressRow>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        items(rows.size) { index ->
            ProgressRowView(row = rows[index])
        }
    }
}
