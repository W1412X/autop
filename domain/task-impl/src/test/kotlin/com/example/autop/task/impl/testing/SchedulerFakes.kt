/**
 * RunScheduler/RunRunner 单测的 fake 端口（docs/tech/07 §9）：日志、屏幕租约、
 * 无障碍、设备状态、用户活动、应用启动与 AgentSession 的可控替身。
 */
@file:Suppress("TooManyFunctions") // 测试替身集合：每个替身恰好实现一个端口，函数数按端口面走

package com.example.autop.task.impl.testing

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AgentSession
import com.example.autop.agent.EventPayload
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunState
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceState
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ScreenLease
import com.example.autop.device.TouchShield
import com.example.autop.device.UserActivityMonitor
import com.example.autop.memory.ExperienceExtractor
import com.example.autop.memory.ExperienceRequest
import com.example.autop.task.RunRecord
import com.example.autop.task.Task
import com.example.autop.task.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** fake 日志：丢弃全部输出。 */
public object FakeLogger : Logger {
    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ): Unit = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ): Unit = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ): Unit = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ): Unit = Unit
}

/** fake 屏幕租约：记录 [withLease] 调用并在执行期间占据 [holder]。 */
public class FakeScreenLease(
    initialHolder: String? = null,
) : ScreenLease {
    private val current = MutableStateFlow(initialHolder)

    override val holder: StateFlow<String?> = current.asStateFlow()

    /** [withLease] 被调用的次数。 */
    public var leaseCalls: Int = 0
        private set

    /** 模拟他人占用（null = 释放）。 */
    public fun setHolder(owner: String?) {
        current.value = owner
    }

    override suspend fun <T> withLease(
        owner: String,
        block: suspend () -> T,
    ): T {
        leaseCalls += 1
        current.value = owner
        try {
            return block()
        } finally {
            current.value = null
        }
    }
}

/** fake 无障碍状态。 */
public class FakeAccessibilityStatus(
    initial: Boolean = true,
) : AccessibilityStatus {
    private val state = MutableStateFlow(initial)

    override val connected: StateFlow<Boolean> = state.asStateFlow()

    /** 模拟无障碍连接状态变化。 */
    public fun setConnected(value: Boolean) {
        state.value = value
    }
}

/** fake 设备状态：可整体替换快照并（可选）发一次变化事件。 */
public class FakeDeviceStateProvider(
    initial: DeviceState,
) : DeviceStateProvider {
    private val changesFlow = MutableSharedFlow<DeviceState>()

    /** 当前状态快照（测试直接改写）。 */
    public var state: DeviceState = initial

    override val changes: Flow<DeviceState> = changesFlow

    override fun current(): DeviceState = state

    /** 发出一次状态变化（触发 RunScheduler 的重试收集器）。 */
    public suspend fun emit(next: DeviceState) {
        state = next
        changesFlow.emit(next)
    }
}

/** fake 用户活动监视器：只提供可改写的最近触摸时间。 */
public class FakeUserActivity : UserActivityMonitor {
    override val userTouches: SharedFlow<Long> = MutableSharedFlow()

    /** 最近一次用户触摸时间戳。 */
    public var lastTouchAt: Long? = null

    override fun lastUserTouchAt(): Long? = lastTouchAt

    override fun markSelfActionWindow(
        startMs: Long,
        endMs: Long,
    ): Unit = Unit
}

/** fake 触摸盾（N186/N191）：记录屏蔽开关与强制解除调用。 */
public class FakeTouchShield : TouchShield {
    /** 最近一次 setActive 的入参。 */
    public var lastActive: Boolean = false

    /** 是否处于强制解除（N191）。 */
    public var forceReleased: Boolean = false

    /** reset 调用次数（N191）。 */
    public var resetCount: Int = 0

    override fun setActive(active: Boolean) {
        this.lastActive = active
    }

    override fun forceRelease() {
        forceReleased = true
    }

    override fun reset() {
        forceReleased = false
        resetCount += 1
    }
}

/** fake 应用启动器：只记录 launch 的包名。 */
public class FakeAppLauncher : AppLauncher {
    /** 已启动的包名（按调用顺序）。 */
    public val launched: MutableList<String> = mutableListOf()

    override suspend fun listApps(query: String?): List<AppInfo> = emptyList()

    override suspend fun launch(packageName: String): Boolean {
        launched += packageName
        return true
    }

    override suspend fun openUri(uri: String): Boolean = true
}

/** fake AgentSession：记录 start 请求并返回立即收口的句柄。 */
public class FakeAgentSession(
    private val finished: RunFinished = RunFinished(status = RunOutcome.SUCCEEDED, summary = "ok"),
) : AgentSession {
    /** 收到的启动请求（按调用顺序）。 */
    public val requests: MutableList<RunRequest> = mutableListOf()

    override fun start(
        request: RunRequest,
        scope: CoroutineScope,
    ): RunHandle {
        requests += request
        return FakeRunHandle(request.runId, finished)
    }

    override suspend fun fork(
        threadId: ThreadId,
        atSeq: Long,
        title: String?,
    ): Outcome<ThreadId> = error("not used")
}

/** fake 运行句柄：[await] 立即返回预设的 [RunFinished]。 */
public class FakeRunHandle(
    override val runId: RunId,
    private val finished: RunFinished,
) : RunHandle {
    override val events: SharedFlow<AgentEvent> = MutableSharedFlow()

    override val state: StateFlow<RunState> = MutableStateFlow(RunState.FINISHED)

    override fun steer(text: String): Unit = Unit

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ): Unit = Unit

    override fun resume(note: String?): Unit = Unit

    override fun cancel(): Unit = Unit

    override suspend fun await(): RunFinished = finished
}

/** fake 线程存储：只提供预置事件列表；[append] 记录写入的事件。 */
public class FakeThreadStore(
    /** [events] 返回的事件。 */
    public val items: List<ThreadEvent> = emptyList(),
) : ThreadStore {
    /** append 写入的事件（供断言）。 */
    public val appended: MutableList<ThreadEvent> = mutableListOf()

    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId = error("not used")

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent {
        val event = ThreadEvent(threadId, appended.size + 1L, runId, 0L, payload)
        appended += event
        return event
    }

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = items

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = items

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = MutableSharedFlow()

    override fun observeChanges(): Flow<Unit> = MutableSharedFlow()

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = error("not used")

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> = emptyList()

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ): Unit = Unit

    override suspend fun titleOf(threadId: ThreadId): String? = null

    override suspend fun delete(threadId: ThreadId): Unit = Unit

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = emptyList()
}

/** fake 任务仓储：记录 save 的任务，get 返回初值或已保存的。 */
public class FakeTaskRepository(
    initial: List<Task> = emptyList(),
) : TaskRepository {
    private val byId = initial.associateBy { it.id }.toMutableMap()

    /** save 过的任务（按调用顺序）。 */
    public val saved: MutableList<Task> = mutableListOf()

    override fun observeTasks(): Flow<List<Task>> = MutableStateFlow(byId.values.toList())

    override suspend fun get(id: TaskId): Task? = byId[id]

    override suspend fun save(task: Task): Outcome<Task> {
        saved += task
        byId[task.id] = task
        return Outcome.Ok(task)
    }

    override suspend fun delete(id: TaskId) {
        byId.remove(id)
    }

    override suspend fun setEnabled(
        id: TaskId,
        enabled: Boolean,
    ): Unit = Unit

    override fun observeRuns(
        taskId: TaskId?,
        limit: Int,
    ): Flow<List<RunRecord>> = MutableStateFlow(emptyList())
}

/** fake 经验提炼：记录收到的请求（供断言）。 */
public class FakeExperienceExtractor : ExperienceExtractor {
    /** 收到的提炼请求（按调用顺序）。 */
    public val requests: MutableList<ExperienceRequest> = mutableListOf()

    override suspend fun extract(request: ExperienceRequest): Boolean {
        requests += request
        return true
    }
}
