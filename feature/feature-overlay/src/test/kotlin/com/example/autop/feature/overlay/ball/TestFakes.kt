package com.example.autop.feature.overlay.ball

import com.example.autop.agent.AgentEvent
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.device.AccessibilityStatus
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** 静默 Logger（测试不需要日志输出）。 */
internal object NoopLogger : Logger {
    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit
}

/** [RunService] 假件：只服务 `observeActive`/`liveEvents`（悬浮球状态推导）。 */
internal class FakeRunService(
    private val active: MutableStateFlow<ActiveRunInfo?> = MutableStateFlow(null),
) : RunService {
    override suspend fun startChat(
        threadId: ThreadId?,
        text: String,
        images: List<com.example.autop.task.ImageAttachment>,
        modelProfileId: String?,
    ): Outcome<RunRecord> = error("unused")

    override suspend fun forkAndRun(
        threadId: ThreadId,
        atSeq: Long,
        text: String?,
    ): Outcome<RunRecord> = error("unused")

    override suspend fun steer(
        runId: RunId,
        text: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override fun pause(runId: RunId) = Unit

    override fun resume(
        runId: RunId,
        note: String?,
    ) = Unit

    override fun cancel(runId: RunId) = Unit

    override suspend fun answer(
        runId: RunId,
        answer: String,
    ) = Unit

    override suspend fun runFlow(
        flowId: com.example.autop.core.common.FlowId,
        params: kotlinx.serialization.json.JsonObject,
        options: com.example.autop.flow.FlowRunOptions,
    ): Outcome<RunRecord> = error("unused")

    override fun flowDebug(runId: RunId): com.example.autop.flow.FlowDebugControl? = null

    override fun flowProgress(runId: RunId): StateFlow<com.example.autop.flow.FlowProgress>? = null

    override suspend fun runTaskNow(task: Task): Outcome<RunRecord> = error("unused")

    override suspend fun resumeInterrupted(runId: RunId): Outcome<RunRecord> = error("unused")

    override fun requeueWaiting(runId: RunId) = Unit

    override fun snoozeWaiting(
        runId: RunId,
        minutes: Int,
    ) = Unit

    override fun skipWaiting(runId: RunId) = Unit

    override fun observeActive(): StateFlow<ActiveRunInfo?> = active

    override fun observeRun(runId: RunId): Flow<RunRecord> = emptyFlow()

    override fun observeOpenRun(threadId: ThreadId): Flow<RunRecord?> = emptyFlow()

    override fun liveEvents(runId: RunId): Flow<AgentEvent> = emptyFlow()
}

/** [AccessibilityStatus] 假件。 */
internal class FakeAccessibilityStatus(
    override val connected: StateFlow<Boolean> = MutableStateFlow(true),
) : AccessibilityStatus
