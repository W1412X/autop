package com.example.autop.feature.chat.thread

import com.example.autop.core.thread.ui.TimelineAction

/** 共享时间线发出的操作映射为对话页的动作（ADR-0031：共享渲染不依赖对话页的动作类型）。 */
internal fun TimelineAction.toChatAction(): ChatThreadAction =
    when (this) {
        is TimelineAction.ShowEntryMenu -> ChatThreadAction.ShowEntryMenu(seq)
        TimelineAction.CopyReply -> ChatThreadAction.CopyReply
        TimelineAction.Resume -> ChatThreadAction.Resume
        TimelineAction.Stop -> ChatThreadAction.Stop
        is TimelineAction.OpenLink -> ChatThreadAction.OpenLink(link)
        is TimelineAction.OpenSourceThread -> ChatThreadAction.OpenSourceThread(threadId)
    }
