package com.example.autop.device.impl.vision

import com.example.autop.device.Rect
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 管线并行行为（ADR-0026）：结果与调度无关、并发上限、图标与 det 并行、叶子间无阶段屏障、失败传播。
 * 用锁存器控制重叠，不 sleep；锁存器等待带超时只用于失败时不卡死。
 */
internal class VisualParsePipelineParallelTest {
    private val pool = Executors.newFixedThreadPool(POOL_THREADS)
    private val dispatcher = pool.asCoroutineDispatcher()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    /** 在途调用计数（det/rec/图标共用，用于核对共享并发上限）。 */
    private class Probe {
        private val inflight = AtomicInteger()
        val peak = AtomicInteger()

        fun <T> enter(block: () -> T): T {
            val now = inflight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                return block()
            } finally {
                inflight.decrementAndGet()
            }
        }
    }

    private class Detector(
        private val dets: List<VisualDetection>,
    ) : VisualDetector {
        override val ready = true
        override val singleClass = false
        override val deployConf: Double? = null

        override fun detect(image: VisionImage): List<VisualDetection> = dets
    }

    private class Locator(
        private val probe: Probe,
        private val onLocate: (Rect) -> Unit = {},
        private val fail: Boolean = false,
    ) : TextDetector {
        override val ready = true

        override fun locate(
            image: VisionImage,
            region: Rect,
        ): List<OcrBox> =
            probe.enter {
                onLocate(region)
                check(!fail) { "det boom" }
                // 上、下两行，中心都在叶子框内（叶子 ≥ 40px，外扩 2/4px）。
                val third = region.height / 3
                listOf(
                    OcrBox(Rect(region.l, region.t + 1, region.r, region.t + third), 0.9),
                    OcrBox(Rect(region.l, region.b - third, region.r, region.b - 1), 0.9),
                )
            }
    }

    private class Recognizer(
        private val probe: Probe,
        private val onRecognize: (OcrBox) -> Unit = {},
    ) : TextRecognizer {
        override val ready = true

        override fun recognize(
            image: VisionImage,
            box: OcrBox,
        ): VisualOcrLine? =
            probe.enter {
                onRecognize(box)
                VisualOcrLine("t${box.bounds.t}l${box.bounds.l}", 0.9, box.bounds)
            }
    }

    private class Icons(
        private val probe: Probe,
        private val onClassify: () -> Unit = {},
    ) : VisualIconClassifier {
        override val ready = true

        override fun classify(
            image: VisionImage,
            candidates: List<IconCandidate>,
        ): Map<Int, String> =
            probe.enter {
                onClassify()
                candidates.indices.associateWith { "icon$it" }
            }
    }

    private object States : VisualStateClassifier {
        override val ready = true

        override fun classify(
            image: VisionImage,
            detections: List<VisualDetection>,
        ): List<VisualDetection> = detections
    }

    /** 4 列 2 行共 8 个互不重叠的叶子，叶子 ≥ 40px。 */
    private fun leaves(): List<VisualDetection> =
        (0 until LEAVES).map { i ->
            val l = (i % 4) * 100 + 5
            val t = (i / 4) * 100 + 5
            VisualDetection(VisualCategory.BUTTON, 0.9, Rect(l, t, l + 80, t + 80))
        }

    private fun image() = VisionImage(400, 200, IntArray(400 * 200) { 0xFFFFFFFF.toInt() })

    private fun parse(
        engines: VisionEngines,
        maxInflight: Int,
    ): VisualParseResult? =
        runBlocking {
            VisualParsePipeline.parse(
                image(),
                engines,
                ParseOptions(minConfidence = 0.35),
                InferenceGate(maxInflight, dispatcher),
            )
        }

    private fun engines(
        probe: Probe,
        locator: TextDetector = Locator(probe),
        recognizer: TextRecognizer = Recognizer(probe),
        icons: VisualIconClassifier? = Icons(probe),
    ) = VisionEngines(Detector(leaves()), locator, recognizer, States, icons)

    @Test
    fun `result is identical whether serial or parallel`() {
        val serial = parse(engines(Probe()), maxInflight = 1)!!
        val parallel = parse(engines(Probe()), maxInflight = 4)!!
        assertThat(parallel.tree.nodes).isEqualTo(serial.tree.nodes)
        assertThat(parallel.lines).isEqualTo(serial.lines)
        assertThat(parallel.iconResults).isEqualTo(serial.iconResults)
        assertThat(serial.lines).hasSize(LEAVES * 2)
    }

    @Test
    fun `in flight calls reach the cap and never exceed it`() {
        val cap = 3
        val probe = Probe()
        val reached = CountDownLatch(cap)
        val timedOut = AtomicBoolean(false)
        val recognizer =
            Recognizer(probe) {
                reached.countDown()
                // 持有许可等到同时有 cap 个调用在途，证明确实并发到了上限；超时只在失败时触发。
                if (!reached.await(WAIT_SECONDS, TimeUnit.SECONDS)) timedOut.set(true)
            }
        parse(engines(probe, recognizer = recognizer), maxInflight = cap)
        assertThat(timedOut.get()).isFalse()
        assertThat(probe.peak.get()).isEqualTo(cap)
    }

    @Test
    fun `icon classification runs while a det call is in flight`() {
        val probe = Probe()
        val iconStarted = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
        val locator =
            Locator(probe, onLocate = {
                // det 一直等到图标分类开始；若图标要等 det 全部结束才启动，这里会超时。
                if (!iconStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)) timedOut.set(true)
            })
        val icons = Icons(probe) { iconStarted.countDown() }
        parse(engines(probe, locator = locator, icons = icons), maxInflight = 2)
        assertThat(timedOut.get()).isFalse()
    }

    @Test
    fun `rec of one leaf overlaps det of another leaf without a stage barrier`() {
        val probe = Probe()
        val recStarted = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
        // 只有最后一个叶子（右下角）的 det 阻塞，等到某个 rec 已经开始才返回；其余 det 立即返回，
        // 并发上限 2 时另一个许可留给 rec。若实现是「全部 det 完成后才开始 rec」，最后这个 det 会因等不到 rec 而超时。
        val locator =
            Locator(probe, onLocate = { region ->
                val lastLeaf = region.l > LAST_LEAF_MIN_X && region.t > LAST_LEAF_MIN_Y
                if (lastLeaf && !recStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)) timedOut.set(true)
            })
        val recognizer = Recognizer(probe) { recStarted.countDown() }
        parse(engines(probe, locator = locator, recognizer = recognizer), maxInflight = 2)
        assertThat(timedOut.get()).isFalse()
    }

    @Test
    fun `inference failure propagates out of parse`() {
        val probe = Probe()
        val error =
            assertThrows(IllegalStateException::class.java) {
                parse(engines(probe, locator = Locator(probe, fail = true)), maxInflight = 4)
            }
        assertThat(error).hasMessageThat().contains("det boom")
    }

    private companion object {
        const val LEAVES = 8
        const val POOL_THREADS = 8
        const val WAIT_SECONDS = 5L

        /** 右下角叶子（第 7 个）的 det 区域起点下限：其余叶子的区域都落在 x ≤ 250 或 y ≤ 50。 */
        const val LAST_LEAF_MIN_X = 250
        const val LAST_LEAF_MIN_Y = 50
    }
}
