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
import com.example.autop.device.DisplayTarget
import com.example.autop.task.impl.testing.FakeRunDisplayController
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
            val holder = MutableStateFlow<String?>("test-run")
            val job =
                launch { TouchShieldWatcher(shield, FakeRunDisplayController()).watch(handle, "test-run", holder) }
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
    public fun `no shield while another run holds the screen`() =
        runTest {
            // ADR-0035：未持有屏幕的 Run 执行态也不屏蔽；持有权转移后才屏蔽。
            val shield = FakeTouchShield()
            val handle = MutableStateHandle(RunState.EXECUTING_TOOLS)
            val holder = MutableStateFlow<String?>("other-run")
            val job =
                launch { TouchShieldWatcher(shield, FakeRunDisplayController()).watch(handle, "test-run", holder) }
            runCurrent()
            assertFalse(shield.lastActive)

            holder.value = "test-run"
            runCurrent()
            assertTrue(shield.lastActive)
            job.cancelAndJoin()
        }

    @Test
    public fun `beginRun resets force release before driving`() =
        runTest {
            // N191/N217（安全关键）：上一 Run 被强制解除后，新 Run 仍能正常屏蔽——
            // reset 在 agent 启动前同步落地，不会晚于用户「停止」的 forceRelease 把盾拉起。
            val shield = FakeTouchShield()
            shield.forceRelease()
            val watcher = TouchShieldWatcher(shield, FakeRunDisplayController())
            watcher.beginRun(RunId("test-run"), DisplayTarget.Main)
            assertFalse(shield.forceReleased)
            val handle = MutableStateHandle(RunState.EXECUTING_TOOLS)
            val holder = MutableStateFlow<String?>("test-run")
            val job = launch { watcher.watch(handle, "test-run", holder) }
            runCurrent()
            assertTrue(shield.lastActive)
            job.cancelAndJoin()
            RunDisplayTargetRegistry.clear(RunId("test-run"))
        }

    @Test
    public fun `background cancel does not force release real shield`() {
        val shield = FakeTouchShield()
        val owner = RunId("background-run")
        RunDisplayTargetRegistry.bind(
            owner,
            DisplayTarget.Background(owner = owner, displayId = 9, width = 1080, height = 1920, dpi = 320),
        )
        try {
            RunDisplayTargetRegistry.forceRelease(owner, shield)
            assertFalse(shield.forceReleased)
        } finally {
            RunDisplayTargetRegistry.clear(owner)
        }
    }

    @Test
    public fun `opening run does not release shared shield before target is confirmed`() {
        val shield = FakeTouchShield()
        val owner = RunId("opening-run")
        RunDisplayTargetRegistry.bindOpening(owner)
        try {
            RunDisplayTargetRegistry.forceRelease(owner, shield)
            assertFalse(shield.forceReleased)

            RunDisplayTargetRegistry.bind(
                owner,
                DisplayTarget.Background(owner = owner, displayId = 9, width = 1080, height = 1920, dpi = 320),
            )
            RunDisplayTargetRegistry.forceRelease(owner, shield)
            assertFalse(shield.forceReleased)
        } finally {
            RunDisplayTargetRegistry.clear(owner)
        }
    }

    @Test
    public fun `real cancel releases shield but unknown target leaves shared shield intact`() {
        val shield = FakeTouchShield()
        val owner = RunId("real-run")
        RunDisplayTargetRegistry.bind(owner, DisplayTarget.Main)
        try {
            RunDisplayTargetRegistry.forceRelease(owner, shield)
            assertTrue(shield.forceReleased)
        } finally {
            RunDisplayTargetRegistry.clear(owner)
        }

        val unknownShield = FakeTouchShield()
        RunDisplayTargetRegistry.forceRelease(RunId("not-started"), unknownShield)
        assertFalse(unknownShield.forceReleased)
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
