/**
 * [ChatListViewModel] 单测（docs/tech/08 §4.1，N184）：首次加载、
 * 外部变更信号即时刷新（不再需要重启应用）、刷新不重置分页进度。
 */
package com.example.autop.feature.chat.list

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.core.common.Capability
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.AccessibilityStatus
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.Task
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `external thread change refreshes the list without reopening`() =
        runTest(testDispatcher) {
            val store = FakeThreadStore(listOf(summary("t1", "旧标题")))
            val viewModel = createViewModel(store)
            assertThat(
                viewModel.uiState.value.threads
                    .map { it.title },
            ).containsExactly("旧标题")

            store.summaries = listOf(summary("t2", "新对话"), summary("t1", "旧标题"))
            store.emitChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            assertThat(
                viewModel.uiState.value.threads
                    .map { it.title },
            ).containsExactly("新对话", "旧标题")
                .inOrder()
        }

    @Test
    fun `refresh keeps already loaded pages`() =
        runTest(testDispatcher) {
            val store = FakeThreadStore((1..25).map { summary("t$it", "标题 $it") })
            val viewModel = createViewModel(store)
            viewModel.onAction(ChatListAction.LoadMore)
            assertThat(viewModel.uiState.value.threads).hasSize(25)

            store.summaries = listOf(summary("t0", "最新")) + store.summaries
            store.emitChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            assertThat(viewModel.uiState.value.threads).hasSize(25)
            assertThat(
                viewModel.uiState.value.threads
                    .first()
                    .title,
            ).isEqualTo("最新")
        }

    private fun createViewModel(store: ThreadStore): ChatListViewModel =
        ChatListViewModel(
            threadStore = store,
            runService = FakeRunService(),
            accessibility = FakeAccessibility(),
            capabilities = FakeCapabilities(),
            configProvider = FakeConfigProvider(),
        )

    private companion object {
        /** 略大于 REFRESH_DEBOUNCE_MS（300ms），确保去抖后的刷新已执行。 */
        const val DEBOUNCE_WAIT_MS = 500L
    }
}

private fun summary(
    id: String,
    title: String,
): ThreadSummary =
    ThreadSummary(
        threadId = ThreadId(id),
        title = title,
        createdAt = 0L,
        updatedAt = 0L,
        preview = null,
        lastRunStatus = null,
    )

/** 可换数据、可发变更信号的 [ThreadStore] 假实现。 */
private class FakeThreadStore(
    var summaries: List<ThreadSummary>,
) : ThreadStore {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** 模拟外部写入（新建 / append / 改名 / 删除）。 */
    fun emitChange() {
        changes.tryEmit(Unit)
    }

    override fun observeChanges(): Flow<Unit> = changes

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> = summaries.drop(offset).take(limit)

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId = error("unused")

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent = error("unused")

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = error("unused")

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = error("unused")

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = emptyFlow()

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = error("unused")

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ): Unit = Unit

    override suspend fun titleOf(threadId: ThreadId): String? = null

    override suspend fun delete(threadId: ThreadId): Unit = Unit

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
}

/** 只用 observeActive 的 [RunService] 假实现。 */
private class FakeRunService : RunService {
    private val active = MutableStateFlow<ActiveRunInfo?>(null)

    override fun observeActive(): StateFlow<ActiveRunInfo?> = active

    override suspend fun startChat(
        threadId: ThreadId?,
        text: String,
        attachCurrentScreen: Boolean,
    ): com.example.autop.core.common.Outcome<RunRecord> = error("unused")

    override suspend fun forkAndRun(
        threadId: ThreadId,
        atSeq: Long,
        text: String?,
    ): com.example.autop.core.common.Outcome<RunRecord> = error("unused")

    override fun steer(
        runId: RunId,
        text: String,
    ): Unit = Unit

    override fun pause(runId: RunId): Unit = Unit

    override fun resume(
        runId: RunId,
        note: String?,
    ): Unit = Unit

    override fun cancel(runId: RunId): Unit = Unit

    override suspend fun answer(
        runId: RunId,
        answer: String,
    ): Unit = Unit

    override suspend fun runTaskNow(task: Task): com.example.autop.core.common.Outcome<RunRecord> = error("unused")

    override suspend fun resumeInterrupted(runId: RunId): com.example.autop.core.common.Outcome<RunRecord> =
        error("unused")

    override fun requeueWaiting(runId: RunId): Unit = Unit

    override fun snoozeWaiting(
        runId: RunId,
        minutes: Int,
    ): Unit = Unit

    override fun skipWaiting(runId: RunId): Unit = Unit

    override fun observeRun(runId: RunId): Flow<RunRecord> = emptyFlow()

    override fun liveEvents(runId: RunId): Flow<AgentEvent> = emptyFlow()
}

private class FakeAccessibility : AccessibilityStatus {
    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
}

private class FakeCapabilities : PlatformCapabilities {
    override fun has(c: Capability): Boolean = false
}
