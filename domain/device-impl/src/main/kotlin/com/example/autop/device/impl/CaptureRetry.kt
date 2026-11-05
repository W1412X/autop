/**
 * 空树重试（docs/tech/05 §2.3，R1/A3）：刚打开应用时 getWindows() 偶尔返回空根，属瞬时状态。
 * 应用窗口没有节点或根节点为空时按 `device.capture.emptyTreeRetries`、
 * `device.capture.emptyTreeRetryDelayMs` 重试采集；仍为空 → `device.service_unavailable`。
 */
package com.example.autop.device.impl

import com.example.autop.core.common.Outcome
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import kotlinx.coroutines.delay

/** 应用窗口空树判定：有 APPLICATION 窗口但没有根或根没有子节点。 */
internal fun hasEmptyAppTree(snapshot: ScreenSnapshot): Boolean =
    snapshot.windows.any { it.type == WindowType.APPLICATION && emptyTree(snapshot, it) }

private fun emptyTree(
    snapshot: ScreenSnapshot,
    window: WindowSnapshot,
): Boolean {
    val rootIdx = window.rootIdx ?: return true
    return snapshot.nodes
        .getOrNull(rootIdx)
        ?.children
        .isNullOrEmpty()
}

/**
 * 采集重试：结果命中 [emptyTree] 时隔 [delayMs] 重试，共 [retries] 次；
 * 仍为空转 [exhausted]，其余结果（含错误）原样返回。
 */
internal suspend fun <T> captureWithEmptyTreeRetry(
    retries: Int,
    delayMs: Long,
    emptyTree: (T) -> Boolean,
    capture: suspend () -> Outcome<T>,
    exhausted: (T) -> Outcome<T>,
): Outcome<T> {
    var attempt = 0
    while (true) {
        val got = capture()
        val ok = got as? Outcome.Ok
        if (ok == null || !emptyTree(ok.value)) return got
        if (attempt >= retries) return exhausted(ok.value)
        attempt++
        delay(delayMs)
    }
}
