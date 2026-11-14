/**
 * 智能体按需占用屏幕的门（ADR-0035）：每个智能体 Run 一个实例。
 * 首次 [acquire] 时才向 [ScreenLease] 取得租约，并记录当时的前台应用（结束后回到原应用）；
 * 之后持有到 [release]（Run 结束，含取消）。
 */
package com.example.autop.task.impl

import com.example.autop.agent.ScreenAccess
import com.example.autop.agent.ScreenAccessGate
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ScreenLease
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** ADR-0035「只聊天」：发送时写入入队变量，值为 "false" 时本轮不提供屏幕工具。 */
internal const val SCREEN_TOOLS_VAR: String = "screenTools"

internal class RunScreenGate(
    private val owner: String,
    private val lease: ScreenLease,
    private val deviceState: DeviceStateProvider,
    private val acquireTimeoutMs: Long,
    private val onAcquired: () -> Unit = {},
) : ScreenAccessGate {
    private val mutex = Mutex()

    @Volatile private var held = false

    /** 首次占用时的前台应用；未占用过为 null。 */
    @Volatile public var originPackage: String? = null
        private set

    override suspend fun acquire(): ScreenAccess =
        mutex.withLock {
            if (held) return ScreenAccess.HELD
            if (!lease.tryAcquire(owner, acquireTimeoutMs)) return ScreenAccess.BUSY
            held = true
            originPackage = deviceState.current().foregroundPackage
            onAcquired()
            ScreenAccess.HELD
        }

    /** Run 结束时调用（幂等）。未持有时无效果。 */
    fun release() {
        if (!held) return
        held = false
        lease.release(owner)
    }
}
