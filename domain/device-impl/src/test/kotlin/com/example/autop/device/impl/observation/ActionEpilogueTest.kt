/**
 * 动作收尾（docs/design/tool_optimize/01 §5、06 §1.2）：
 * 智能体路径按 device.action.delayMs 固定等待、trace 末行为等待行、不观测；
 * quietActions 等界面稳定、不追加等待行、不生成回放；preSample 的采样来源与失败语义。
 */
package com.example.autop.device.impl.observation

import android.content.Context
import android.graphics.Bitmap
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.StableWait
import com.example.autop.device.UiStabilizer
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.core.RedactionMode
import com.example.autop.device.impl.AppIdentity
import com.example.autop.device.impl.AutopAccessibilityService
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.ForegroundTracker
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.SerializerRulesLoader
import com.example.autop.device.impl.ServiceHolder
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.WindowRedactor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ActionEpilogueTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val provider = FakeConfigProvider(Json.parseToJsonElement("{}").jsonObject)

    @Test
    fun `agent path waits device action delayMs and appends waited line`() =
        runTest {
            val probe = RecordingProbe(Outcome.Ok(sample()))
            val monitor = RecordingMonitor()
            val epilogue = epilogue(probe, monitor)
            val ctx = ctx()

            val out =
                epilogue.finish(
                    ctx,
                    "已点击 (1, 1)",
                    null,
                    ReplayInput("tool.tap", JsonObject(emptyMap())),
                    sample(),
                )

            assertThat(currentTime).isEqualTo(5000)
            assertThat(out.observation).isNull()
            val waited = out.trace.last()
            assertThat(waited).contains("5000")
            assertThat(out.content.joinToString("\n")).doesNotContain("效果：")
            assertThat(out.replay).isNotNull()
        }

    @Test
    fun `wait_ms overrides the default delay`() =
        runTest {
            val epilogue = epilogue(RecordingProbe(Outcome.Ok(sample())), RecordingMonitor())
            val ctx = ctx()

            epilogue.finish(ctx, "已点击 (1, 1)", 1200, ReplayInput("tool.tap", JsonObject(emptyMap())), sample())

            assertThat(currentTime).isEqualTo(1200)
        }

    @Test
    fun `wait_ms above max is clamped and noted`() =
        runTest {
            val epilogue = epilogue(RecordingProbe(Outcome.Ok(sample())), RecordingMonitor())
            val ctx = ctx()

            val out =
                epilogue.finish(
                    ctx,
                    "已点击 (1, 1)",
                    40000,
                    ReplayInput("tool.tap", JsonObject(emptyMap())),
                    sample(),
                )

            assertThat(currentTime).isEqualTo(30000)
            val joined = out.trace.joinToString("\n")
            assertThat(joined).contains("30000")
            assertThat(out.trace.size).isAtLeast(2)
        }

    @Test
    fun `quietActions waits for stability without waited line and without replay`() =
        runTest {
            val stabilizer = RecordingStabilizer()
            val epilogue = epilogue(RecordingProbe(Outcome.Ok(sample())), RecordingMonitor(), stabilizer)
            val ctx = ctx(quiet = true)

            val out = epilogue.finish(ctx, "已滚动 2 次", 1200, ReplayInput("tool.scroll", JsonObject(emptyMap())), null)

            assertThat(stabilizer.calls).isEqualTo(1)
            assertThat(out.trace).isEmpty()
            assertThat(out.replay).isNull()
        }

    @Test
    fun `preSample uses a11y probe when no observation yet`() =
        runTest {
            val probe = RecordingProbe(Outcome.Ok(sample()))
            val epilogue = epilogue(probe, RecordingMonitor())
            val ctx = ctx()

            val pre = epilogue.preSample(ctx)

            assertThat(pre).isNotNull()
            assertThat(probe.calls).isEqualTo(1)
        }

    @Test
    fun `preSample reuses last visual observation without probing`() =
        runTest {
            val reader = FakeReader(jpegBytes(GRAY))
            val visual = FakeVisual(listOf(visualTree("条目")))
            val probe = RecordingProbe(Outcome.Ok(sample()))
            val (observer, epilogue) = fixture(reader, visual, probe)
            val ctx = ctx()
            okOf(observer.observe(ctx, ObserveSelection.Explicit(ObserveMode.VISUAL)))

            val pre = epilogue.preSample(ctx)

            assertThat(pre).isNotNull()
            assertThat(probe.calls).isEqualTo(0)
        }

    @Test
    fun `preSample failure returns null and finish records no replay`() =
        runTest {
            val probe = RecordingProbe(Outcome.Err(AppError("test.no_sample", "sample failed")))
            val epilogue = epilogue(probe, RecordingMonitor())
            val ctx = ctx()

            val pre = epilogue.preSample(ctx)
            assertThat(pre).isNull()

            val out = epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())), pre)
            assertThat(out.replay).isNull()
        }

    @Test
    fun `self action window is registered before and after the wait`() =
        runTest {
            val monitor = RecordingMonitor()
            val epilogue = epilogue(RecordingProbe(Outcome.Ok(sample())), monitor)
            val ctx = ctx()

            epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())), sample())

            assertThat(monitor.windows).hasSize(2)
            assertThat(monitor.windows[0].first).isEqualTo(0L)
        }

    private fun epilogue(
        probe: ReplayStateProbe,
        monitor: UserActivityMonitor,
        stabilizer: UiStabilizer = FakeStabilizer,
    ): ActionEpilogue = ActionEpilogue(stabilizer, probe, frameMotion(), FakePromptRepository(), FakeClock(0L), monitor)

    private fun fixture(
        reader: FakeReader,
        visual: FakeVisual,
        probe: ReplayStateProbe,
    ): Pair<ScreenObserver, ActionEpilogue> {
        val prompts = FakePromptRepository()
        val contentAreas = ContentAreaProvider(provider)
        val holder = ServiceHolder().apply { register(service()) }
        val rules = SerializerRulesLoader(context)
        val serializer = SnapshotSerializer(rules, NoRedaction, AppIdentity(context), prompts)
        val capturer =
            SnapshotCapturer(
                holder = holder,
                configProvider = provider,
                clock = FakeClock(0L),
                foreground = ForegroundTracker(),
                contentAreas = contentAreas,
                prompts = prompts,
                context = context,
            )
        val modes = ObserveModeResolver(holder, visual, prompts)
        val preparer = ScreenPreparer(reader, capturer, contentAreas, visual, serializer)
        val observer = ScreenObserver(preparer, serializer, modes)
        val epilogue = ActionEpilogue(FakeStabilizer, probe, frameMotion(), prompts, FakeClock(0L), RecordingMonitor())
        return Pair(observer, epilogue)
    }

    private fun service(): AutopAccessibilityService =
        Robolectric.buildService(AutopAccessibilityService::class.java).get()

    private fun ctx(quiet: Boolean = false): ToolContext = TestContext(provider, quiet)

    private fun sample(): ReplayStateSample =
        ReplayStateSample(
            packageName = APP,
            activity = "$APP.MainActivity",
            fingerprint = "fp-1",
            keys = emptyList(),
            semanticsVersion = 3,
            observeMode = "a11y",
        )

    private fun <T> okOf(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }

    private fun visualTree(label: String): VisualTree {
        val root =
            NodeSnapshot(
                idx = 0,
                children = (1..5).toList(),
                childIndex = 0,
                className = "visual.FrameLayout",
                bounds = FULL,
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
            )
        val children =
            (1..5).map { index ->
                NodeSnapshot(
                    idx = index,
                    parent = 0,
                    children = emptyList(),
                    childIndex = index - 1,
                    className = "visual.Button",
                    text = "$label$index",
                    bounds = Rect(0, index * 100, 600, index * 100 + 50),
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
                )
            }
        return VisualTree(rootIdx = 0, nodes = listOf(root) + children)
    }

    private fun jpegBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class FakeReader(
        private val jpeg: ByteArray,
    ) : ScreenReader {
        override suspend fun snapshot(): Outcome<ScreenSnapshot> = error("not used")

        override suspend fun screenshot(): Outcome<Screenshot> =
            Outcome.Ok(Screenshot(width = 64, height = 64, jpegBytes = jpeg, handle = Any()))
    }

    private class FakeVisual(
        trees: List<VisualTree> = emptyList(),
    ) : VisualScreenParser {
        private val pending = ArrayDeque(trees)

        override val available: Boolean = true

        override suspend fun synthesize(frame: VisualFrame): VisualTree? {
            check(pending.isNotEmpty()) { "no visual tree queued" }
            return pending.removeFirst()
        }

        override suspend fun warmUp() = Unit
    }

    private object FakeStabilizer : UiStabilizer {
        override suspend fun awaitStable(
            quietMs: Long,
            maxWaitMs: Long,
            firstEventTimeoutMs: Long,
            sampleFingerprint: suspend () -> String?,
        ): StableWait = StableWait(0L, settled = true)
    }

    private class RecordingStabilizer : UiStabilizer {
        var calls: Int = 0

        override suspend fun awaitStable(
            quietMs: Long,
            maxWaitMs: Long,
            firstEventTimeoutMs: Long,
            sampleFingerprint: suspend () -> String?,
        ): StableWait {
            calls += 1
            return StableWait(0L, settled = true)
        }
    }

    /** 触摸监测替身：记录自身动作窗口登记。 */
    private class RecordingMonitor : UserActivityMonitor {
        val windows: MutableList<Pair<Long, Long>> = mutableListOf()

        override val userTouches: SharedFlow<Long> = MutableSharedFlow()

        override fun lastUserTouchAt(): Long? = null

        override fun markSelfActionWindow(
            startMs: Long,
            endMs: Long,
        ) {
            windows += startMs to endMs
        }
    }

    private class RecordingProbe(
        private val result: Outcome<ReplayStateSample>,
    ) : ReplayStateProbe {
        var calls: Int = 0

        override suspend fun sample(
            observeMode: String,
            config: ConfigSnapshot,
        ): Outcome<ReplayStateSample> {
            calls += 1
            return result
        }
    }

    private fun frameMotion(): FrameMotion =
        FrameMotion(FakeReader(byteArrayOf()), FakeConfigProvider(Json.parseToJsonElement("{}").jsonObject))

    private object NoRedaction : WindowRedactor {
        override fun redact(
            window: WindowSnapshot,
            config: ConfigSnapshot,
        ): RedactionMode = RedactionMode.NONE
    }

    private class TestContext(
        private val provider: FakeConfigProvider,
        private val quiet: Boolean,
    ) : ToolContext {
        override val runId: RunId = RunId("run-1")
        override val threadId: ThreadId = ThreadId("thread-1")
        override val config: ConfigSnapshot get() = provider.current()
        override val runScope: RunScopedValues = MapRunScope()
        override val threadStore: ThreadStore = UnusedThreadStore
        override val quietActions: Boolean get() = quiet

        override suspend fun askUser(
            question: String,
            options: List<String>,
            timeoutMs: Long,
        ): Outcome<String> = Outcome.Err(AppError("test.unused", "askUser is not used"))

        override fun emitProgress(text: String) = Unit
    }

    private class MapRunScope : RunScopedValues {
        private val values = mutableMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getOrPut(
            key: String,
            create: () -> T,
        ): T = values.getOrPut(key) { create() } as T
    }

    private object UnusedThreadStore : ThreadStore {
        override suspend fun createThread(
            title: String?,
            parent: Pair<ThreadId, Long>?,
        ): ThreadId = unused()

        override suspend fun append(
            threadId: ThreadId,
            runId: RunId?,
            payload: EventPayload,
        ): ThreadEvent = unused()

        override suspend fun events(
            threadId: ThreadId,
            fromSeq: Long,
            toSeq: Long,
        ): List<ThreadEvent> = unused()

        override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = unused()

        override fun observe(threadId: ThreadId): Flow<ThreadEvent> = emptyFlow()

        override fun observeChanges(): Flow<Unit> = emptyFlow()

        override suspend fun saveBlob(
            threadId: ThreadId,
            name: String,
            bytes: ByteArray,
        ): String = unused()

        override suspend fun listThreads(
            offset: Int,
            limit: Int,
        ): List<ThreadSummary> = unused()

        override suspend fun rename(
            threadId: ThreadId,
            title: String,
        ) = unused()

        override suspend fun modelProfileOf(threadId: ThreadId): String? = null

        override suspend fun setModelProfile(
            threadId: ThreadId,
            profileId: String?,
        ) = Unit

        override suspend fun titleOf(threadId: ThreadId): String? = unused()

        override suspend fun delete(threadId: ThreadId) = unused()

        override suspend fun forkablePoints(threadId: ThreadId): List<Long> = unused()

        private fun unused(): Nothing = error("ThreadStore is not used in this test")
    }

    private companion object {
        const val APP: String = "com.example.app"
        const val SCREEN_WIDTH: Int = 1080
        const val SCREEN_HEIGHT: Int = 1920
        const val GRAY: Int = -8355712
        val FULL: Rect = Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
    }
}
