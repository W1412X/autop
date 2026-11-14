/**
 * 屏幕独占租约实现（docs/tech/05 §1.3，ADR-0035 按需占用）：同一时刻只有一个 owner 操作屏幕。
 * [withLease] 与 [tryAcquire] 共用同一把锁，同一 owner 的嵌套调用直接执行（不重复排队）。
 */
package com.example.autop.device.impl

import com.example.autop.device.ScreenLease
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 单例租约：[holder] 暴露当前持有者（无持有者为 null）。 */
public class ScreenLeaseImpl : ScreenLease {
    private val mutex = Mutex()
    private val _holder = MutableStateFlow<String?>(null)

    override val holder: StateFlow<String?> = _holder.asStateFlow()

    override suspend fun <T> withLease(
        owner: String,
        block: suspend () -> T,
    ): T {
        // 同一 owner 的嵌套调用直接执行（外层已持锁，再排队会死锁）。
        if (_holder.value == owner) return block()
        mutex.withLock {
            _holder.value = owner
            try {
                return block()
            } finally {
                _holder.value = null
            }
        }
    }

    override suspend fun tryAcquire(
        owner: String,
        timeoutMs: Long,
    ): Boolean {
        if (_holder.value == owner) return true
        val locked = withTimeoutOrNull(timeoutMs) { mutex.lock() } != null
        if (!locked) return false
        _holder.value = owner
        return true
    }

    override fun release(owner: String) {
        if (_holder.value != owner) return
        _holder.value = null
        mutex.unlock()
    }
}
