/**
 * shell 通道后台保活（N203）：已配对断开重连 / 未配对不打扰 / 已连接不动作 /
 * 失败退避重试 / stop 停止巡逻。
 */
package com.example.autop.device.impl.shell

import com.example.autop.core.common.Outcome
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** 巡逻间隔（与 ShellKeepAliveImpl 一致，N203）。 */
private const val TICK_MS = 15_000L

/** 失败重试间隔（与 ShellKeepAliveImpl 一致，N203）。 */
private const val RETRY_MS = 30_000L

class ShellKeepAliveTest {
    @Test
    fun `reconnects when paired and disconnected`() =
        runTest {
            val shell = FakeShellInput()
            shell.paired.value = true
            val keepAlive = ShellKeepAliveImpl(shell)

            keepAlive.start(backgroundScope)
            testScheduler.runCurrent()

            assertThat(shell.connectCalls).isEqualTo(1)
            assertThat(shell.state.value).isEqualTo(ShellState.Connected)
        }

    @Test
    fun `does not connect when never paired`() =
        runTest {
            val shell = FakeShellInput()
            val keepAlive = ShellKeepAliveImpl(shell)

            keepAlive.start(backgroundScope)
            testScheduler.advanceTimeBy(TICK_MS * 4)
            testScheduler.runCurrent()

            assertThat(shell.connectCalls).isEqualTo(0)
        }

    @Test
    fun `does not touch healthy connection`() =
        runTest {
            val shell = FakeShellInput()
            shell.paired.value = true
            shell.state.value = ShellState.Connected
            val keepAlive = ShellKeepAliveImpl(shell)

            keepAlive.start(backgroundScope)
            testScheduler.advanceTimeBy(TICK_MS * 4)
            testScheduler.runCurrent()

            assertThat(shell.connectCalls).isEqualTo(0)
        }

    @Test
    fun `retries after failed reconnect with backoff`() =
        runTest {
            val shell = FakeShellInput(connectResults = mutableListOf(false, true))
            shell.paired.value = true
            val keepAlive = ShellKeepAliveImpl(shell)

            keepAlive.start(backgroundScope)
            testScheduler.runCurrent()
            assertThat(shell.connectCalls).isEqualTo(1)

            // 失败后按重试间隔退避：间隔内不打扰，到点再试。
            testScheduler.advanceTimeBy(RETRY_MS - 1)
            testScheduler.runCurrent()
            assertThat(shell.connectCalls).isEqualTo(1)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertThat(shell.connectCalls).isEqualTo(2)
            assertThat(shell.state.value).isEqualTo(ShellState.Connected)
        }

    @Test
    fun `stop halts patrol`() =
        runTest {
            val shell = FakeShellInput()
            shell.paired.value = true
            val keepAlive = ShellKeepAliveImpl(shell)

            keepAlive.start(this)
            testScheduler.runCurrent()
            keepAlive.stop()
            testScheduler.advanceTimeBy(TICK_MS * 4)
            testScheduler.runCurrent()

            assertThat(shell.connectCalls).isEqualTo(1)
        }
}

/** 假 ShellInput：connect 结果可编排（默认成功并置 Connected）。 */
private class FakeShellInput(
    private val connectResults: MutableList<Boolean> = mutableListOf(),
) : ShellInput {
    override val state = MutableStateFlow<ShellState>(ShellState.Disconnected)

    override val paired = MutableStateFlow(false)

    var connectCalls: Int = 0

    override suspend fun pair(
        pairingPort: Int,
        pairCode: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun connect(): Boolean {
        connectCalls += 1
        val ok = if (connectResults.isNotEmpty()) connectResults.removeAt(0) else true
        state.value = if (ok) ShellState.Connected else ShellState.Disconnected
        return ok
    }

    override suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean = true

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = true

    override suspend fun keyEvent(code: Int): Boolean = true

    override suspend fun keyCombination(vararg codes: Int): Boolean = true
}
