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

    /** 当前持有者（无持有者为 null）。 */
    public val holder: StateFlow<String?>
}
