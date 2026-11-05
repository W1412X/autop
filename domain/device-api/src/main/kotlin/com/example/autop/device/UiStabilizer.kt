/**
 * 界面稳定等待端口（docs/tech/05 §1.3、§2.5）。
 */
package com.example.autop.device

/** 动作后等待界面稳定。 */
public interface UiStabilizer {
    /**
     * 动作后等待界面稳定（docs/tech/05 §2.5，R1/A2）：
     *
     * ① 先等第一个无障碍事件（最多 [firstEventTimeoutMs]，一直没有事件即认为界面没变化直接返回 0）；
     * ② 收到事件后等待静默 [quietMs]；
     * ③ 到 [maxWaitMs] 上限时，连续两次 [sampleFingerprint]（ScreenFingerprint）相同也算稳定。
     *
     * 返回实际等待时长（超时不是错误）。
     */
    public suspend fun awaitStable(
        quietMs: Long,
        maxWaitMs: Long,
        firstEventTimeoutMs: Long,
        sampleFingerprint: suspend () -> String?,
    ): Long
}
