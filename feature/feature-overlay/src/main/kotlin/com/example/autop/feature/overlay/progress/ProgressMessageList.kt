/**
 * 进度消息流（进度悬浮窗与顶部运行详情面板共用，ADR-0039）：对话页同款 [TimelineRow]，
 * 宽度随窗口内容区（用户气泡限宽），新行自动滚到底。横幅条目不在消息流里渲染，由 [AlertCards] 固定在底部。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.autop.core.thread.ui.TimelineAction
import com.example.autop.core.thread.ui.TimelineEntry
import com.example.autop.core.thread.ui.TimelineItem
import com.example.autop.core.thread.ui.TimelineRow
import com.example.autop.core.thread.ui.TimelineStyle
import com.example.autop.core.ui.component.rememberStickToBottom
import com.example.autop.core.ui.theme.LocalSpacing

/** 消息流：[entries] 为归并后的时间线条目（横幅除外），[onAction] 处理条目动作。 */
@Composable
internal fun ProgressMessageList(
    entries: List<TimelineEntry>,
    onAction: (TimelineAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = entries.filterNot { it.item is TimelineItem.Banner }
    val listState = rememberLazyListState()
    rememberStickToBottom(listState)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val style = TimelineStyle(availableWidth = maxWidth)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
        ) {
            items(count = messages.size) { index ->
                TimelineRow(
                    entry = messages[index],
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                )
            }
        }
    }
}
