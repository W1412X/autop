/**
 * 动作收尾（agent-harness-overhaul WP2.11）：效果回执由 ScreenDelta 渲染；
 * 显式 llm 用画面帧签名比较且不调视觉大模型；观测失败保持动作成功。
 */
package com.example.autop.device.impl.observation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ScreenDeltaKind
import com.example.autop.agent.ScreenDeltaReasons
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
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Observation
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenQuality
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
import com.example.autop.device.WindowType
import com.example.autop.device.core.RedactionMode
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.AppIdentity
import com.example.autop.device.impl.AutopAccessibilityService
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.ForegroundTracker
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.SerializerRulesLoader
import com.example.autop.device.impl.ServiceHolder
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.WindowRedactor
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.vision.FrameSignature
import com.example.autop.device.impl.vision.frameSignatureOf
import com.example.autop.device.impl.vision.llm.LlmScreenParser
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.ModelRole
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `visual unchanged screen yields UNCHANGED effect line`() =
        runTest {
            val reader = FakeReader(jpegBytes(GRAY))
            val visual = FakeVisual(listOf(visualTree("条目"), visualTree("条目")))
            val (observer, epilogue) = fixture(reader, visual)
            val ctx = ctx()
            okOf(observer.observe(ctx, ObserveSelection.Explicit(ObserveMode.VISUAL)))

            val out = epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())))

            val effect = out.content.last().toString()
            assertThat(effect).contains("效果：界面没有变化（页面元素与动作前一致")
            assertThat(effect).contains("device.no_effect")
            assertThat(out.screenDelta?.kind).isEqualTo(ScreenDeltaKind.UNCHANGED)
        }

    @Test
    fun `visual changed screen yields CHANGED`() =
        runTest {
            val reader = FakeReader(jpegBytes(GRAY))
            val visual = FakeVisual(listOf(visualTree("条目"), visualTree("已改")))
            val (observer, epilogue) = fixture(reader, visual)
            val ctx = ctx()
            okOf(observer.observe(ctx, ObserveSelection.Explicit(ObserveMode.VISUAL)))

            val out = epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())))

            val effect = out.content.last().toString()
            assertThat(effect).contains("效果：界面已变化（页面内容有变化）")
            assertThat(out.screenDelta?.kind).isEqualTo(ScreenDeltaKind.CHANGED)
        }

    @Test
    fun `explicit llm uses frame signature and does not call llm parser`() =
        runTest {
            val bytes = jpegBytes(GRAY)
            val reader = FakeReader(bytes)
            val (observer, epilogue, gateway) = fixture(reader, FakeVisual())
            val ctx = ctx()
            ctx.deviceHistory().last =
                RecordedObservation(
                    mode = ObserveMode.LLM,
                    selection = ObserveSelection.Explicit(ObserveMode.LLM),
                    screen = llmScreen(),
                    snapshot = a11ySnapshot(sufficientNodes()),
                    frame = signatureOf(bytes),
                )

            val out = epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())))

            assertThat(gateway.calls).isEqualTo(0)
            val effect = out.content.last().toString()
            assertThat(effect).contains("效果：界面没有变化（按画面比较")
            assertThat(out.screenDelta?.kind).isEqualTo(ScreenDeltaKind.UNCHANGED)
            assertThat(out.screenDelta?.reason).isEqualTo(ScreenDeltaReasons.FRAME)
            assertThat(out.observation).isNull()
            assertThat(out.replay).isNull()
        }

    @Test
    fun `observe failure keeps ok true and UNKNOWN observe_failed`() =
        runTest {
            val reader =
                FakeReader(
                    jpegBytes(GRAY),
                    snapshots =
                        listOf(
                            Outcome.Ok(a11ySnapshot(sufficientNodes())),
                            Outcome.Err(
                                AppError(DeviceErrorCodes.APP_TREE_EMPTY, "app accessibility tree is empty"),
                            ),
                        ),
                )
            val (observer, epilogue, _) = fixture(reader, FakeVisual())
            val ctx = ctx()
            okOf(observer.observe(ctx, ObserveSelection.Explicit(ObserveMode.A11Y)))

            val out = epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())))

            assertThat(out.ok).isTrue()
            val effect = out.content.last().toString()
            assertThat(effect).contains("效果：无法判断，动作后的观测失败（${DeviceErrorCodes.APP_TREE_EMPTY}）")
            assertThat(out.screenDelta?.kind).isEqualTo(ScreenDeltaKind.UNKNOWN)
            assertThat(out.screenDelta?.reason).isEqualTo(ScreenDeltaReasons.OBSERVE_FAILED)
        }

    @Test
    fun `no baseline yields UNKNOWN no_baseline`() =
        runTest {
            val reader =
                FakeReader(
                    jpegBytes(GRAY),
                    snapshots = listOf(Outcome.Ok(a11ySnapshot(sufficientNodes()))),
                )
            val (observer, epilogue, _) = fixture(reader, FakeVisual())
            val ctx = ctx()

            val out = epilogue.finish(ctx, "已点击 (1, 1)", null, ReplayInput("tool.tap", JsonObject(emptyMap())))

            assertThat(out.ok).isTrue()
            val effect = out.content.last().toString()
            assertThat(effect).contains("效果：无法判断，本次运行在这个动作之前还没有观测")
            assertThat(out.screenDelta?.kind).isEqualTo(ScreenDeltaKind.UNKNOWN)
            assertThat(out.screenDelta?.reason).isEqualTo(ScreenDeltaReasons.NO_BASELINE)
        }

    private fun fixture(
        reader: FakeReader,
        visual: FakeVisual,
    ): Triple<ScreenObserver, ActionEpilogue, CountingGateway> {
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
        val llm =
            LlmScreenParser(
                context = context,
                gateway = CountingGateway,
                prompts = prompts,
                rules = rules,
                configProvider = provider,
                dispatchers = TestDispatcherProvider(StandardTestDispatcher()),
                clock = FakeClock(0L),
            )
        val modes = ObserveModeResolver(holder, visual, CountingGateway, prompts)
        val preparer = ScreenPreparer(reader, capturer, contentAreas, visual, llm, serializer)
        val observer = ScreenObserver(preparer, serializer, modes)
        val frameMotion = FrameMotion(reader, provider)
        val epilogue =
            ActionEpilogue(FakeStabilizer, observer, FakeProbe, frameMotion, prompts, FakeClock(0L), FakeMonitor)
        return Triple(observer, epilogue, CountingGateway)
    }

    private fun service(): AutopAccessibilityService =
        Robolectric.buildService(AutopAccessibilityService::class.java).get()

    private fun ctx(): ToolContext = TestContext(provider)

    private fun <T> okOf(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }

    private fun a11ySnapshot(nodes: List<NodeSnapshot>): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 0L,
            windows =
                listOf(
                    WindowSnapshot(
                        windowId = 1,
                        type = WindowType.APPLICATION,
                        layer = 0,
                        packageName = APP,
                        title = "示例",
                        bounds = FULL,
                        active = true,
                        focused = true,
                        rootIdx = 0,
                    ),
                ),
            nodes = nodes,
            foregroundPackage = APP,
            activity = "$APP.MainActivity",
            screenWidth = SCREEN_WIDTH,
            screenHeight = SCREEN_HEIGHT,
            rotation = 0,
            keyguardLocked = false,
            contentArea = FULL,
            truncated = false,
        )

    private fun sufficientNodes(): List<NodeSnapshot> {
        val root =
            NodeSnapshot(
                idx = 0,
                children = (1..5).toList(),
                childIndex = 0,
                className = "android.widget.FrameLayout",
                bounds = FULL,
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
                packageName = APP,
            )
        val children =
            (1..5).map { index ->
                NodeSnapshot(
                    idx = index,
                    parent = 0,
                    children = emptyList(),
                    childIndex = index - 1,
                    className = "android.widget.Button",
                    text = "按钮$index",
                    bounds = Rect(0, index * 100, 600, index * 100 + 50),
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
                    packageName = APP,
                )
            }
        return listOf(root) + children
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

    private fun llmScreen(): SerializedScreen =
        SerializedScreen(
            Observation(
                text = "{}",
                mode = ObservationMode.FULL,
                observeMode = ObserveMode.LLM,
                fingerprint = "fp",
                packageName = APP,
                activity = "$APP.MainActivity",
                summaryLine = "app",
                interactiveCount = 1,
                nodeCount = 1,
                semanticPoor = false,
            ),
            ScreenModel(mode = ObserveMode.LLM, windows = emptyList(), quality = ScreenQuality(1, 1)),
        )

    private fun jpegBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun signatureOf(bytes: ByteArray): FrameSignature {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return frameSignatureOf(bitmap)
    }

    private class FakeReader(
        private val jpeg: ByteArray,
        snapshots: List<Outcome<ScreenSnapshot>> = emptyList(),
    ) : ScreenReader {
        private val pending = ArrayDeque(snapshots)

        override suspend fun snapshot(): Outcome<ScreenSnapshot> {
            check(pending.isNotEmpty()) { "no snapshot queued" }
            return pending.removeFirst()
        }

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

    /** 触摸监测替身（WP7.3：收尾会登记自身动作窗口，这里只记录不判定）。 */
    private object FakeMonitor : UserActivityMonitor {
        override val userTouches: SharedFlow<Long> = MutableSharedFlow()

        override fun lastUserTouchAt(): Long? = null

        override fun markSelfActionWindow(
            startMs: Long,
            endMs: Long,
        ) = Unit
    }

    private object FakeProbe : ReplayStateProbe {
        override suspend fun sample(
            observeMode: String,
            config: ConfigSnapshot,
        ): Outcome<ReplayStateSample> = Outcome.Err(AppError("test.unused", "probe is not used"))
    }

    /** 视觉大模型网关替身：计数调用，保证 llm 收尾不打大模型。 */
    private object CountingGateway : LlmGateway {
        var calls: Int = 0

        override fun forRole(role: ModelRole): Outcome<LlmProvider> {
            calls++
            return Outcome.Err(AppError(LlmErrorCodes.NOT_CONFIGURED, "no vision model"))
        }

        override fun forProfile(profileId: String): Outcome<LlmProvider> {
            calls++
            return Outcome.Err(AppError(LlmErrorCodes.NOT_CONFIGURED, "no vision model"))
        }
    }

    private object NoRedaction : WindowRedactor {
        override fun redact(
            window: WindowSnapshot,
            config: ConfigSnapshot,
        ): RedactionMode = RedactionMode.NONE
    }

    private class TestContext(
        private val provider: FakeConfigProvider,
    ) : ToolContext {
        override val runId: RunId = RunId("run-1")
        override val threadId: ThreadId = ThreadId("thread-1")
        override val config: ConfigSnapshot get() = provider.current()
        override val runScope: RunScopedValues = MapRunScope()
        override val threadStore: ThreadStore = UnusedThreadStore

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
