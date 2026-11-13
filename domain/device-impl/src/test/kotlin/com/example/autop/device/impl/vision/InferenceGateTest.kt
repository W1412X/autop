package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 推理闸门与并行度解析（ADR-0026）。 */
internal class InferenceGateTest {
    private val pool = Executors.newFixedThreadPool(POOL_THREADS)
    private val dispatcher = pool.asCoroutineDispatcher()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    @Test
    fun `in flight calls never exceed the cap and reach it`() {
        val cap = 3
        val gate = InferenceGate(cap, dispatcher)
        val inflight = AtomicInteger()
        val peak = AtomicInteger()
        val reached = CountDownLatch(cap)
        runBlocking {
            coroutineScope {
                (1..10)
                    .map {
                        async {
                            gate.run {
                                // 先自增再更新峰值：updateAndGet 的 lambda 可能因 CAS 重试而重复执行，不能带副作用。
                                val now = inflight.incrementAndGet()
                                peak.updateAndGet { maxOf(it, now) }
                                reached.countDown()
                                reached.await(WAIT_SECONDS, TimeUnit.SECONDS)
                                inflight.decrementAndGet()
                            }
                        }
                    }.awaitAll()
            }
        }
        assertThat(peak.get()).isEqualTo(cap)
    }

    @Test
    fun `cancelled waiter gives its place back`() {
        val gate = InferenceGate(1, dispatcher)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = AtomicInteger()
        runBlocking {
            val holder =
                launch(dispatcher) {
                    gate.run {
                        holding.countDown()
                        release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    }
                }
            // 等持有者拿到许可（在 Default 线程上阻塞等，不占用 runBlocking 的事件循环线程）。
            withContext(dispatcher) { holding.await(WAIT_SECONDS, TimeUnit.SECONDS) }
            // UNDISPATCHED：等待者内联跑到第一次挂起（排上队等许可）才返回，随后取消的是「已排队」的等待者。
            val waiter =
                launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                    gate.run { done.incrementAndGet() }
                }
            waiter.cancel(CancellationException("test"))
            waiter.join()
            release.countDown()
            holder.join()
            gate.run { done.addAndGet(RESULT_MARK) }
        }
        // 被取消的等待者没有执行；之后的调用仍能拿到许可。
        assertThat(done.get()).isEqualTo(RESULT_MARK)
    }

    @Test
    fun `max inflight is bounded by configured value and spare cores`() {
        assertThat(VisionParallelism.maxInflight(4, 8)).isEqualTo(4)
        assertThat(VisionParallelism.maxInflight(4, 4)).isEqualTo(3)
        assertThat(VisionParallelism.maxInflight(4, 2)).isEqualTo(1)
        assertThat(VisionParallelism.maxInflight(4, 1)).isEqualTo(1)
        assertThat(VisionParallelism.maxInflight(1, 8)).isEqualTo(1)
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val POOL_THREADS = 8
        const val RESULT_MARK = 100
    }
}
