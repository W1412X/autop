/**
 * Run 详情页用户意图（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.run

import com.example.autop.core.thread.ui.TimelineAction

/** Run 详情页意图。 */
public sealed interface RunDetailAction {
    /** 在这个 Run 的基础上继续（分叉线程再跑一次）。 */
    public data object Continue : RunDetailAction

    /** 中断恢复：同线程接着做（docs/tech/04 §3.11）。 */
    public data object ResumeInterrupted : RunDetailAction

    /** 返回。 */
    public data object Back : RunDetailAction

    /** 过程条目发出的动作（快捷入口、长按、分叉标记、暂停横幅等）。 */
    public data class Timeline(
        val action: TimelineAction,
    ) : RunDetailAction

    /** 长按菜单：从第 [seq] 条事件分叉。 */
    public data class Fork(
        val seq: Long,
    ) : RunDetailAction

    /** 长按菜单：查看第 [seq] 条事件的原始数据。 */
    public data class ShowRaw(
        val seq: Long,
    ) : RunDetailAction

    /** 关闭长按菜单。 */
    public data object DismissEntryMenu : RunDetailAction

    /** 关闭原始数据弹窗。 */
    public data object DismissRaw : RunDetailAction
}
