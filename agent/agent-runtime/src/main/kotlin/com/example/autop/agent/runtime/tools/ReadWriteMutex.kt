/**
 * 读写锁（docs/tech/04 §3.3）：读共享、写独占。基于 Mutex + 读计数自实现。
 */
package com.example.autop.agent.runtime.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 屏幕锁与记忆锁的共用实现。 */
internal class ReadWriteMutex {
    private val writeMutex = Mutex()
    private val counterMutex = Mutex()
    private var readers = 0

    /** 读共享：首个读者占住写锁，最后一个读者释放。 */
    suspend fun <T> withRead(block: suspend () -> T): T {
        counterMutex.withLock {
            readers += 1
            if (readers == 1) writeMutex.lock()
        }
        try {
            return block()
        } finally {
            counterMutex.withLock {
                readers -= 1
                if (readers == 0) writeMutex.unlock()
            }
        }
    }

    /** 写独占。 */
    suspend fun <T> withWrite(block: suspend () -> T): T = writeMutex.withLock { block() }
}
