/**
 * 贴底跟随（对话页、进度面板、AI 创建页共用）：停在底部时内容增长自动跟随，上滑即停止。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlin.coroutines.cancellation.CancellationException

/** 贴底跟随的开关状态。 */
public class StickToBottomState internal constructor() {
    private var following by mutableStateOf(true)
    internal var request: Int by mutableStateOf(0)
        private set

    /** 当前是否跟随底部。 */
    public val isFollowing: Boolean
        get() = following

    /** 恢复跟随，并立即贴底一次（发送消息后不必等下一次布局变化）。 */
    public fun follow() {
        following = true
        request++
    }

    /** 停止跟随：把视图停在当前位置（用户上滑或页面主动定位到别处）。 */
    public fun release() {
        following = false
    }
}

/**
 * 贴底跟随：列表停在底部时，内容或布局增长（流式文本、新条目）自动滚到底部；
 * 手指一拖动就停止，滚回底部后自动恢复。
 *
 * 只观察布局变化，不依赖调用方传入的内容指纹，因此流式文本变长同样会跟随。
 */
@Composable
public fun rememberStickToBottom(listState: LazyListState): StickToBottomState {
    val state = remember(listState) { StickToBottomState() }
    LaunchedEffect(listState, state) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) state.release()
        }
    }
    LaunchedEffect(listState, state) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !listState.canScrollForward) state.follow()
        }
    }
    LaunchedEffect(listState, state) {
        snapshotFlow { listState.layoutInfo to state.request }.collect {
            if (state.isFollowing) stickToBottom(listState)
        }
    }
    return state
}

/** 贴到列表末尾：末项不可见时先定位到末项，再补齐末项底部与视口底部的差值。 */
private suspend fun stickToBottom(listState: LazyListState) {
    val info = listState.layoutInfo
    val lastIndex = info.totalItemsCount - 1
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    try {
        if (last.index < lastIndex) {
            listState.scrollToItem(lastIndex)
            return
        }
        val viewportBottom = info.viewportEndOffset - info.afterContentPadding
        val delta = viewportBottom - (last.offset + last.size)
        // 只向下贴底；内容不足一屏时 delta 为正，不能再滚动（否则会反复触发自身）。
        if (delta < 0) listState.scrollBy(delta.toFloat())
    } catch (_: CancellationException) {
        // 用户手势接管滚动：本次贴底作废，跟随状态已由 DragInteraction 分支关闭。
    }
}
