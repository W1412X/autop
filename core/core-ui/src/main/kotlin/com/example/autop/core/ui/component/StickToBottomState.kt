/**
 * 贴底跟随（对话页、进度面板、AI 创建页共用）：停在底部时内容增长自动跟随，上滑即停止。
 */
package com.example.autop.core.ui.component

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

/**
 * 贴到列表末尾：末项不可见时先定位到末项；末项高过视口、底部仍在视口外时，
 * 再用 scrollToItem 的偏移量把超出部分卷进去（懒列表会在内容末端钳制住）。
 *
 * 已经能向前滚动才动作：贴底后布局仍会重测，若无条件滚动会自触发形成死循环。
 */
private suspend fun stickToBottom(listState: LazyListState) {
    val info = listState.layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0 || !listState.canScrollForward) return
    val last = info.visibleItemsInfo.lastOrNull()
    if (last == null || last.index < lastIndex) {
        listState.scrollToItem(lastIndex)
        return
    }
    val viewportBottom = info.viewportEndOffset - info.afterContentPadding
    val overflow = last.offset + last.size - viewportBottom
    try {
        listState.scrollToItem(lastIndex, overflow.coerceAtLeast(0))
    } catch (_: CancellationException) {
        // 用户手势接管滚动：本次贴底作废，跟随状态已由 DragInteraction 分支关闭。
    }
}
