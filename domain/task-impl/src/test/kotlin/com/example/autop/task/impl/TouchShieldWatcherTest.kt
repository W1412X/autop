/**
 * [TouchShieldWatcher] 单测（docs/tech/07 §4.3，N186）：屏蔽状态矩阵与
 * watch 的状态驱动、Run 结束兜底解除。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunHandle
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.task.impl.testing.FakeTouchShield
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [shouldShield] 矩阵与 [TouchShieldWatcher.watch] 驱动路径。 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent（TestScope 调度推进）
public class TouchShieldWatcherTest {
    @Test
    public fun `only executing states shield touches`() {
        assertTrue(shouldShield(RunState.STARTING))
        assertTrue(shouldShield(RunState.SAMPLING))
        assertTrue(shouldShield(RunState.EXECUTING_TOOLS))
        assertTrue(shouldShield(RunState.COMPACTING))
        assertFalse(shouldShield(RunState.PAUSED))
        assertFalse(shouldShield(RunState.WAITING_USER))
        assertFalse(shouldShield(RunState.FINISHED))
    }

    @Test
    public fun `watch drives shield by state and releases on end`() =
        runTest {
            val shield = FakeTouchShield()
            val handle = MutableStateHandle(RunState.STARTING)
            val job = launch { TouchShieldWatcher(shield).watch(handle) }
            runCurrent()
            assertTrue(shield.lastActive)

            handle.flow.value = RunState.WAITING_USER
            runCurrent()
            assertFalse(shield.lastActive)

            handle.flow.value = RunState.EXECUTING_TOOLS
            runCurrent()
            assertTrue(shield.lastActive)

            // Run 结束（watch 协程取消）→ finally 兜底解除。
            job.cancelAndJoin()
            assertFalse(shield.lastActive)
        }

    @Test
    public fun `beginRun resets force release before driving`() =
        runTest {
            // N191/N217（安全关键）：上一 Run 被强制解除后，新 Run 仍能正常屏蔽——
            // reset 在 agent 启动前同步落地，不会晚于用户「停止」的 forceRelease 把盾拉起。
            val shield = FakeTouchShield()
            shield.forceRelease()
            val watcher = TouchShieldWatcher(shield)
            watcher.beginRun()
            assertFalse(shield.forceReleased)
            val handle = MutableStateHandle(RunState.EXECUTING_TOOLS)
            val job = launch { watcher.watch(handle) }
            runCurrent()
            assertTrue(shield.lastActive)
            job.cancelAndJoin()
        }
}

/** fake 运行句柄：state 可变（驱动测试用），await 不返回。 */
private class MutableStateHandle(
    initial: RunState,
) : RunHandle {
    val flow: MutableStateFlow<RunState> = MutableStateFlow(initial)

    override val runId: RunId = RunId("test-run")

    override val events: SharedFlow<AgentEvent> = MutableSharedFlow()

    override val state: StateFlow<RunState> = flow

    override fun steer(text: String): Unit = Unit

    override fun pause(
        reason: PauseReason,
        detail: String?,
    ): Unit = Unit

    override fun resume(note: String?): Unit = Unit

    override fun cancel(): Unit = Unit

    override suspend fun await(): RunFinished = RunFinished(status = RunOutcome.CANCELLED, summary = null)
}
