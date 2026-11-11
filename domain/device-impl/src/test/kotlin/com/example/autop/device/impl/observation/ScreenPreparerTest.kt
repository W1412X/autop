/**
 * 单方式观测采集（agent-harness-overhaul WP7.7 / WP7.6）：截图错误码原样返回，
 * 解析器异常转 `device.visual_parse_failed`，不伪装成空树。
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
import com.example.autop.device.impl.ObserveSelection
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.SerializerRulesLoader
import com.example.autop.device.impl.ServiceHolder
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.WindowRedactor
import com.example.autop.device.impl.deviceHistory
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

/** AccessibilityNodeInfo.ACTION_CLICK（节点动作 id）。 */
private const val ACTION_ID_CLICK = 16

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScreenPreparerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val provider = FakeConfigProvider(Json.parseToJsonElement("{}").jsonObject)

    @Test
    fun `screenshot error code is preserved for visual`() =
        runTest {
            val secure = AppError(code = DeviceErrorCodes.SCREENSHOT_SECURE, message = "secure window")
            val reader = FakeReader(Outcome.Err(secure))
            val preparer = fixture(reader, FakeVisual())

            val got = preparer.prepare(ctx(), ObserveMode.VISUAL, null, listOf(ObserveMode.VISUAL))

            check(got is Outcome.Err)
            assertThat(got.error.code).isEqualTo(DeviceErrorCodes.SCREENSHOT_SECURE)
            assertThat(got.error.message).isEqualTo("secure window")
        }

    @Test
    fun `parser exception becomes visual_parse_failed`() =
        runTest {
            val reader =
                FakeReader(Outcome.Ok(Screenshot(width = 1, height = 1, jpegBytes = ByteArray(0), handle = Any())))
            val preparer = fixture(reader, FakeVisual(boom = IllegalStateException("boom")))

            val got = preparer.prepare(ctx(), ObserveMode.VISUAL, null, listOf(ObserveMode.VISUAL))

            check(got is Outcome.Err)
            assertThat(got.error.code).isEqualTo(DeviceErrorCodes.VISUAL_PARSE_FAILED)
            assertThat(got.error.message).isEqualTo("boom")
        }

    @Test
    fun `node ids persist across observations and reset on page change`() =
        runTest {
            val ctx = ctx()
            val reader = FakeReader(Outcome.Err(AppError("test.unused", "shot")))
            val preparer = fixture(reader, FakeVisual())
            val snap = a11ySnapshot(activity = "com.example.app.MainActivity")

            reader.snap = Outcome.Ok(snap)
            val first = preparer.prepare(ctx, ObserveMode.A11Y, null, listOf(ObserveMode.A11Y))
            check(first is Outcome.Ok)
            val firstModel = first.value.serialized.model
            val firstIds = firstModel.flatten().map { it.id }
            assertThat(firstIds).containsExactly(1, 2).inOrder()

            // 记录上次观测后再次观测（内容不变）：id 沿用。
            record(ctx, first.value, snap)
            val second = preparer.prepare(ctx, ObserveMode.A11Y, null, listOf(ObserveMode.A11Y))
            check(second is Outcome.Ok)
            val secondModel = second.value.serialized.model
            val secondIds = secondModel.flatten().map { it.id }
            assertThat(secondIds).containsExactly(1, 2).inOrder()

            // 换页后不配对：全新 id（计数器继续走，不复用）。
            record(ctx, second.value, snap)
            reader.snap = Outcome.Ok(a11ySnapshot(activity = "com.example.app.OtherActivity"))
            val third = preparer.prepare(ctx, ObserveMode.A11Y, null, listOf(ObserveMode.A11Y))
            check(third is Outcome.Ok)
            val thirdModel = third.value.serialized.model
            val thirdIds = thirdModel.flatten().map { it.id }
            assertThat(thirdIds).containsExactly(3, 4).inOrder()
        }

    /** 记录一次观测到 Run 历史（ScreenObserver.record 的最小等价）。 */
    private fun record(
        ctx: ToolContext,
        prepared: Prepared,
        snapshot: ScreenSnapshot,
    ) {
        ctx.deviceHistory().last =
            RecordedObservation(
                mode = prepared.mode,
                selection = ObserveSelection.Auto,
                screen = prepared.serialized,
                snapshot = snapshot,
                frame = null,
            )
    }

    /** 两个可点击叶子的 a11y 快照。 */
    private fun a11ySnapshot(activity: String): ScreenSnapshot {
        val leaf = { idx: Int, text: String, top: Int ->
            NodeSnapshot(
                idx = idx,
                parent = 1,
                children = emptyList(),
                childIndex = idx - 2,
                className = "android.widget.Button",
                text = text,
                bounds = Rect(0, top, 200, top + 80),
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
                actions = listOf(ACTION_ID_CLICK),
                packageName = "com.example.app",
            )
        }
        val root =
            NodeSnapshot(
                idx = 1,
                parent = null,
                children = listOf(2, 3),
                childIndex = 0,
                className = "android.widget.FrameLayout",
                bounds = Rect(0, 0, 1080, 1920),
                flags = NodeFlags.VISIBLE or NodeFlags.ENABLED,
                packageName = "com.example.app",
            )
        val window =
            WindowSnapshot(
                windowId = 1,
                type = WindowType.APPLICATION,
                layer = 0,
                packageName = "com.example.app",
                title = null,
                bounds = Rect(0, 0, 1080, 1920),
                active = true,
                focused = true,
                rootIdx = 1,
            )
        return ScreenSnapshot(
            capturedAt = 0L,
            windows = listOf(window),
            nodes = listOf(root, leaf(2, "确定", 100), leaf(3, "取消", 300)),
            foregroundPackage = "com.example.app",
            activity = activity,
            screenWidth = 1080,
            screenHeight = 1920,
            rotation = 0,
            keyguardLocked = false,
            truncated = false,
        )
    }

    private fun fixture(
        reader: FakeReader,
        visual: FakeVisual,
    ): ScreenPreparer {
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
        return ScreenPreparer(reader, capturer, contentAreas, visual, llm, serializer)
    }

    private fun service(): AutopAccessibilityService =
        Robolectric.buildService(AutopAccessibilityService::class.java).get()

    private fun ctx(): ToolContext = TestContext(provider)

    /** 截图替身：按脚本返回 [Outcome]；[snap] 可在测试中途更换（id 跨观测用例）。 */
    private class FakeReader(
        private val shot: Outcome<Screenshot>,
    ) : ScreenReader {
        var snap: Outcome<ScreenSnapshot> = Outcome.Err(AppError("test.unused", "snapshot is not used"))

        override suspend fun snapshot(): Outcome<ScreenSnapshot> = snap

        override suspend fun screenshot(): Outcome<Screenshot> = shot
    }

    /** 视觉解析替身：可配置抛出解析器异常（WP7.6）。 */
    private class FakeVisual(
        private val boom: Throwable? = null,
    ) : VisualScreenParser {
        override val available: Boolean = true

        override suspend fun synthesize(frame: VisualFrame): VisualTree {
            boom?.let { throw it }
            error("synthesize should not succeed in this test")
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
            window: com.example.autop.device.WindowSnapshot,
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
}
