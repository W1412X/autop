/**
 * 屏幕独占租约（docs/tech/05 §1.3）：同一时刻只允许一个 Run 操作屏幕。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.StateFlow

/** 屏幕独占。 */
public interface ScreenLease {
    /** 以 [owner] 身份持有租约执行 [block]（可重入同一 owner）。 */
    public suspend fun <T> withLease(
        owner: String,
        block: suspend () -> T,
    ): T

    /**
     * 按需取得租约（ADR-0035）：取得后持有到 [release]，期间 [withLease] 的其他 owner 会等待。
     * 同一 owner 重复调用直接返回 true。[timeoutMs] 内取不到返回 false，不无限等待。
     */
    public suspend fun tryAcquire(
        owner: String,
        timeoutMs: Long,
    ): Boolean

    /** 释放 [owner] 持有的租约；不是持有者时无效果。 */
    public fun release(owner: String)

    /** 当前持有者（无持有者为 null）。 */
    public val holder: StateFlow<String?>
}
