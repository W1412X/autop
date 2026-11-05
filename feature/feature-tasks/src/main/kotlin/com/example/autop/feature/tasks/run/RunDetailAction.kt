/**
 * Run 详情页用户意图（docs/tech/08 §4.3）。
 */
package com.example.autop.feature.tasks.run

/** Run 详情页意图。 */
public sealed interface RunDetailAction {
    /** 在这个 Run 的基础上继续（分叉线程再跑一次）。 */
    public data object Continue : RunDetailAction

    /** 中断恢复：同线程接着做（docs/tech/04 §3.11）。 */
    public data object ResumeInterrupted : RunDetailAction

    /** 返回。 */
    public data object Back : RunDetailAction
}
