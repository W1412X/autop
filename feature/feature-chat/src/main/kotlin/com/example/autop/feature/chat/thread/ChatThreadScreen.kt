/**
 * 对话页（docs/tech/08 §4.2）：顶栏（更多菜单）+ 无障碍提示 + 时间线（自动滚底、
 * 回到底部按钮）+ AskCard + 输入区。无状态 Composable，状态与操作走
 * [ChatThreadUiState] / [ChatThreadAction]。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.example.autop.core.config.UiBannerSection
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.InlineBanner
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.chat.R
import kotlinx.coroutines.launch

/**
 * 对话页。
 *
 * @param state 页面状态。
 * @param onAction 用户操作回调。
 * @param onBack 返回上一级（顶栏返回按钮）。
 */
@Composable
public fun ChatThreadScreen(
    state: ChatThreadUiState,
    onAction: (ChatThreadAction) -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        ThreadHeader(state = state, onAction = onAction, onBack = onBack)
        ThreadTimeline(
            entries = state.entries,
            onAction = onAction,
            modifier = Modifier.weight(1f),
        )
        state.waitingQuestion?.let { question ->
            AskCard(
                question = question,
                onAnswer = { text -> onAction(ChatThreadAction.Answer(text)) },
                modifier =
                    Modifier.padding(
                        horizontal = LocalSpacing.current.screenEdge,
                        vertical = LocalSpacing.current.sm,
                    ),
            )
        }
        InputArea(
            state = state,
            onAction = onAction,
            focusRequester = focusRequester,
            modifier = Modifier.padding(bottom = LocalSpacing.current.md),
        )
    }
    ThreadSheets(state = state, onAction = onAction)
}

/** 顶栏（更多菜单）+ 无障碍未开启横幅 + 一次性提示横幅。 */
@Composable
private fun ThreadHeader(
    state: ChatThreadUiState,
    onAction: (ChatThreadAction) -> Unit,
    onBack: () -> Unit,
) {
    SubPageTopBar(
        title = state.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.thread_new_title),
        onBack = onBack,
        actions = {
            AutopIconButton(
                icon = AutopIcons.MoreVert,
                contentDescription = stringResource(R.string.thread_more_cd),
                onClick = { onAction(ChatThreadAction.ShowMoreMenu) },
            )
        },
    )
    if (!state.accessibilityConnected) {
        StatusBanner(
            tone = StatusTone.BLOCKED,
            title = stringResource(R.string.chat_a11y_title),
            message = stringResource(R.string.chat_a11y_body),
            modifier =
                Modifier.padding(
                    horizontal = LocalSpacing.current.screenEdge,
                    vertical = LocalSpacing.current.sm,
                ),
        )
    }
    state.notice?.let { notice ->
        InlineBanner(
            message = noticeText(notice),
            onDismiss = { onAction(ChatThreadAction.DismissNotice) },
            durationMs = UiBannerSection().durationMs,
            modifier =
                Modifier.padding(
                    horizontal = LocalSpacing.current.screenEdge,
                    vertical = LocalSpacing.current.sm,
                ),
        )
    }
}

/** 时间线：新条目到达时若在底部则自动滚底（docs/tech/08 §4.2），上滑后显示"回到底部"。 */
@Composable
private fun ThreadTimeline(
    entries: List<TimelineEntry>,
    onAction: (ChatThreadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motion = LocalMotionEnabled.current
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && atBottom) {
            scrollToEnd(listState, motion)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.listGap),
            contentPadding =
                PaddingValues(
                    horizontal = LocalSpacing.current.screenEdge,
                    vertical = LocalSpacing.current.sm,
                ),
        ) {
            items(count = entries.size) { index ->
                TimelineRow(
                    entry = entries[index],
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!atBottom && entries.isNotEmpty()) {
            AutopTonalButton(
                text = stringResource(R.string.thread_back_bottom),
                onClick = {
                    scope.launch { scrollToEnd(listState, motion) }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = LocalSpacing.current.md),
            )
        }
    }
}

private suspend fun scrollToEnd(
    listState: LazyListState,
    animate: Boolean,
) {
    val lastIndex = listState.layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (animate) {
        listState.animateScrollToItem(lastIndex)
    } else {
        listState.scrollToItem(lastIndex)
    }
}

@Composable
private fun noticeText(notice: ThreadNotice): String =
    stringResource(
        when (notice) {
            ThreadNotice.EXPORT_LOG -> R.string.thread_notice_export
            ThreadNotice.COPIED -> R.string.thread_notice_copied
            ThreadNotice.IMAGE_FAILED -> R.string.thread_notice_image_failed
        },
    )
