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
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import com.example.autop.device.core.RedactionMode
import com.example.autop.device.impl.AppIdentity
import com.example.autop.device.impl.AutopAccessibilityService
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.ForegroundTracker
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

    /** 截图替身：按脚本返回 [Outcome]。 */
    private class FakeReader(
        private val shot: Outcome<Screenshot>,
    ) : ScreenReader {
        override suspend fun snapshot(): Outcome<ScreenSnapshot> = error("snapshot is not used")

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
