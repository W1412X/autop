package com.example.autop.core.thread.ui

import com.example.autop.agent.timeline.StepLink

/**
 * 时间线条目发出的用户操作（ADR-0031）。对话页与运行详情各自把它映射到自己的动作类型，
 * 共享渲染本身不依赖任何页面。
 */
public sealed interface TimelineAction {
    /** 长按时间线条目（打开条目菜单）。 */
    public data class ShowEntryMenu(
        val seq: Long,
    ) : TimelineAction

    /** 元信息行“复制”图标：文本由 UI 写剪贴板，动作只用于提示“已复制”。 */
    public data object CopyReply : TimelineAction

    /** 继续（暂停、拦截横幅的操作按钮）。 */
    public data object Resume : TimelineAction

    /** 停止运行中的 Run（拦截横幅的操作按钮）。 */
    public data object Stop : TimelineAction

    /** 点击步骤行的快捷入口。 */
    public data class OpenLink(
        val link: StepLink,
    ) : TimelineAction

    /** 打开分叉来源会话（分叉标记）。 */
    public data class OpenSourceThread(
        val threadId: String,
    ) : TimelineAction
}
