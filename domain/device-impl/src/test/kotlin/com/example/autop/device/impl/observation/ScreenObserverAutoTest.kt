/**
 * 自动观测选择（agent-harness-overhaul WP1.11）：优先级、auto 开关、信息不足回退、
 * 显式空树错误与差量基准。
 */
package com.example.autop.device.impl.observation

import android.content.Context
import com.example.autop.agent.EventPayload
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
import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ObservationMode
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
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
import com.example.autop.device.impl.vision.llm.LlmScreenParser
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.ModelRole
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScreenObserverAutoTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `auto returns a11y when sufficient and never calls visual`() =
        runTest {
            val visual = FakeVisual()
            val observer = observer(FakeReader(listOf(Outcome.Ok(a11ySnapshot(sufficientNodes())))), visual)
            val result = observe(observer)

            assertThat(result.screen.model.mode).isEqualTo(ObserveMode.A11Y)
            assertThat(visual.calls).isEqualTo(0)
            assertThat(result.observation.mode).isEqualTo(ObservationMode.FULL)
        }

    @Test
    fun `auto skips empty a11y tree to visual with note`() =
        runTest {
            val visual = FakeVisual(listOf(visualTree(visualNodes(count = 5))))
            val observer = observer(FakeReader(listOf(Outcome.Err(emptyTree()))), visual)
            val result = observe(observer)

            assertThat(visual.calls).isEqualTo(1)
            assertThat(result.screen.model.mode).isEqualTo(ObserveMode.VISUAL)
            assertThat(result.observation.text).contains("（自动选择 visual")
            assertThat(result.observation.text).contains("a11y")
        }

    @Test
    fun `auto skips mode with auto disabled`() =
        runTest {
            val visual = FakeVisual(listOf(visualTree(visualNodes(count = 5))))
            val provider = provider("""{"device":{"observe":{"modes":{"visual":{"auto":false}}}}}""")
            val observer = observer(FakeReader(listOf(Outcome.Err(emptyTree()))), visual, provider)
            val result = observer.observe(ctx(provider), ObserveSelection.Auto)

            assertThat(visual.calls).isEqualTo(0)
            val error = errorOf(result)
            assertThat(error.code).isEqualTo(DeviceErrorCodes.NO_OBSERVATION)
        }

    @Test
    fun `all insufficient returns last success with note`() =
        runTest {
            val visual = FakeVisual(listOf(visualTree(visualNodes(count = 1))))
            val observer = observer(FakeReader(listOf(Outcome.Ok(a11ySnapshot(insufficientNodes())))), visual)
            val result = observe(observer)

            assertThat(visual.calls).isEqualTo(1)
            assertThat(result.screen.model.mode).isEqualTo(ObserveMode.VISUAL)
            assertThat(result.observation.text).contains("信息都不足")
            assertThat(result.observation.text).contains("visual")
        }

    @Test
    fun `explicit a11y with empty tree returns app_tree_empty error`() =
        runTest {
            val observer = observer(FakeReader(listOf(Outcome.Err(emptyTree()))), FakeVisual())
            val result = observer.observe(ctx(), ObserveSelection.Explicit(ObserveMode.A11Y))

            assertThat(errorOf(result).code).isEqualTo(DeviceErrorCodes.APP_TREE_EMPTY)
        }

    @Test
    fun `record diffs against last only when same mode`() =
        runTest {
            val visual = FakeVisual(listOf(visualTree(visualNodes(count = 5))))
            val snapshots = List(3) { Outcome.Ok(a11ySnapshot(sufficientNodes())) }
            val observer = observer(FakeReader(snapshots), visual)
            val context = ctx()

            val first = okOf(observer.observe(context, ObserveSelection.Explicit(ObserveMode.A11Y)))
            val second = okOf(observer.observe(context, ObserveSelection.Explicit(ObserveMode.A11Y)))
            val third = okOf(observer.observe(context, ObserveSelection.Explicit(ObserveMode.VISUAL)))

            assertThat(first.observation.mode).isEqualTo(ObservationMode.FULL)
            assertThat(second.observation.mode).isEqualTo(ObservationMode.DIFF)
            assertThat(third.observation.mode).isEqualTo(ObservationMode.FULL)
            assertThat(third.screen.model.mode).isEqualTo(ObserveMode.VISUAL)
        }

    private suspend fun observe(observer: ScreenObserver): ObservationResult =
        okOf(observer.observe(ctx(), ObserveSelection.Auto))

    private fun <T> okOf(outcome: Outcome<T>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }

    private fun <T> errorOf(outcome: Outcome<T>): AppError =
        when (outcome) {
            is Outcome.Err -> outcome.error
            is Outcome.Ok -> error("expected error, was ok")
        }

    private fun ctx(provider: FakeConfigProvider = provider()): ToolContext = TestContext(provider)

    private fun observer(
        reader: FakeReader,
        visual: FakeVisual,
        provider: FakeConfigProvider = provider(),
    ): ScreenObserver {
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
                gateway = NoVisionGateway,
                prompts = prompts,
                rules = rules,
                configProvider = provider,
                dispatchers = TestDispatcherProvider(StandardTestDispatcher()),
                clock = FakeClock(0L),
            )
        val modes = ObserveModeResolver(holder, visual, NoVisionGateway, prompts)
        val preparer = ScreenPreparer(reader, capturer, contentAreas, visual, llm, serializer)
        return ScreenObserver(preparer, serializer, modes)
    }

    private fun service(): AutopAccessibilityService =
        Robolectric.buildService(AutopAccessibilityService::class.java).get()

    private fun provider(overrides: String = "{}"): FakeConfigProvider =
        FakeConfigProvider(Json.parseToJsonElement(overrides).jsonObject)

    private fun emptyTree(): AppError =
        AppError(
            code = DeviceErrorCodes.APP_TREE_EMPTY,
            message = "app accessibility tree is empty",
            retryable = true,
        )

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

    private fun sufficientNodes(): List<NodeSnapshot> = a11yNodes(count = 5, clickable = true)

    private fun insufficientNodes(): List<NodeSnapshot> = a11yNodes(count = 1, clickable = false)

    private fun a11yNodes(
        count: Int,
        clickable: Boolean,
    ): List<NodeSnapshot> {
        val root =
            NodeSnapshot(
                idx = 0,
                children = (1..count).toList(),
                childIndex = 0,
                className = "android.widget.FrameLayout",
                bounds = FULL,
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
                packageName = APP,
            )
        val children =
            (1..count).map { index ->
                NodeSnapshot(
                    idx = index,
                    parent = 0,
                    children = emptyList(),
                    childIndex = index - 1,
                    className = "android.widget.Button",
                    text = "按钮$index",
                    bounds = Rect(0, index * ROW_HEIGHT, 600, index * ROW_HEIGHT + ROW_HEIGHT / 2),
                    flags =
                        NodeFlags.VISIBLE or NodeFlags.ENABLED or
                            if (clickable) NodeFlags.CLICKABLE else 0,
                    packageName = APP,
                )
            }
        return listOf(root) + children
    }

    private fun visualTree(nodes: List<NodeSnapshot>): VisualTree = VisualTree(rootIdx = 0, nodes = nodes)

    private fun visualNodes(count: Int): List<NodeSnapshot> {
        val root =
            NodeSnapshot(
                idx = 0,
                children = (1..count).toList(),
                childIndex = 0,
                className = "visual.FrameLayout",
                bounds = FULL,
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
            )
        val children =
            (1..count).map { index ->
                NodeSnapshot(
                    idx = index,
                    parent = 0,
                    children = emptyList(),
                    childIndex = index - 1,
                    className = "visual.Button",
                    text = "条目$index",
                    bounds = Rect(0, index * ROW_HEIGHT, 600, index * ROW_HEIGHT + ROW_HEIGHT / 2),
                    flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
                )
            }
        return listOf(root) + children
    }

    private class FakeReader(
        snapshots: List<Outcome<ScreenSnapshot>>,
    ) : ScreenReader {
        private val pending = ArrayDeque(snapshots)

        override suspend fun snapshot(): Outcome<ScreenSnapshot> {
            check(pending.isNotEmpty()) { "no snapshot queued" }
            return pending.removeFirst()
        }

        override suspend fun screenshot(): Outcome<Screenshot> =
            Outcome.Ok(Screenshot(width = SCREEN_WIDTH, height = SCREEN_HEIGHT, handle = Any()))
    }

    private class FakeVisual(
        trees: List<VisualTree> = emptyList(),
    ) : VisualScreenParser {
        private val pending = ArrayDeque(trees)
        var calls: Int = 0

        override val available: Boolean = true

        override suspend fun synthesize(frame: VisualFrame): VisualTree? {
            calls++
            check(pending.isNotEmpty()) { "no visual tree queued" }
            return pending.removeFirst()
        }

        override suspend fun warmUp() = Unit
    }

    private object NoVisionGateway : LlmGateway {
        override fun forRole(role: ModelRole): Outcome<LlmProvider> =
            Outcome.Err(AppError(LlmErrorCodes.NOT_CONFIGURED, "no vision model"))

        override fun forProfile(profileId: String): Outcome<LlmProvider> =
            Outcome.Err(AppError(LlmErrorCodes.NOT_CONFIGURED, "no vision model"))
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
        const val ROW_HEIGHT: Int = 100
        val FULL: Rect = Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
    }
}
