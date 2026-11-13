/**
 * 视觉推理闸门（ADR-0026）：det / rec / 图标分类共用一个「同时在途的推理调用数」上限。
 * 许可只在单次推理调用期间持有，不跨阶段持有，因此一个任务做完 det 释放许可再排队拿 rec 许可，不会死锁。
 * 引擎接口是阻塞调用，闸门负责把它放到 [dispatcher] 上执行；取消时许可自动归还，
 * 取消粒度是「一次推理调用」（阻塞中的 ORT 调用无法中断）。
 */
package com.example.autop.device.impl.vision

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 推理并发闸门。[maxInflight] ≤ 1 即完全串行。 */
internal class InferenceGate(
    maxInflight: Int,
    private val dispatcher: CoroutineDispatcher,
) {
    private val permits = Semaphore(maxOf(1, maxInflight))

    /** 取得许可后在 [dispatcher] 上执行阻塞的 [block]；FIFO 排队。 */
    suspend fun <T> run(block: () -> T): T = permits.withPermit { withContext(dispatcher) { block() } }
}

/** 并行度解析（`device.vision.parallel`）。 */
internal object VisionParallelism {
    /** 配置的并发上限与设备核数取小：至少留一个核给界面与 agent，最少 1。 */
    fun maxInflight(
        configured: Int,
        cpuCores: Int,
    ): Int = maxOf(1, minOf(configured, cpuCores - 1))
}
