/**
 * `state.input` 的 shell 四态文案（agent-harness-overhaul WP3.10）：
 * Connected / Connecting / Disconnected+已配对 / Disconnected+未配对。
 */
package com.example.autop.device.impl.state

import com.example.autop.agent.StateRequest
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InputStateSourceTest {
    @Test
    fun `shell four states map to four texts`() =
        runTest {
            val cases =
                listOf(
                    ShellState.Connected to "shell 注入（等同真实触摸）",
                    ShellState.Connecting to "shell 正在连接",
                    ShellState.Disconnected to "shell 未配对",
                )
            for ((state, fragment) in cases) {
                val shell = FakeShell(state = state, paired = false)
                val texts = shellTexts(shell)
                // coordinate-node-action-split §4.1：四种状态都写明“坐标操作当前模式”。
                assertThat(texts).contains("坐标操作（tap、swipe、scroll、global_action）当前模式")
                assertThat(texts).contains(fragment)
            }
            val paired = FakeShell(state = ShellState.Disconnected, paired = true)
            assertThat(shellTexts(paired)).contains("shell 未连接，已配对过")
        }

    private suspend fun shellTexts(shell: FakeShell): String {
        val source =
            InputStateSource(
                accessibility = FakeAccessibility,
                shell = shell,
                prompts = FakePromptRepository(),
            )
        val req =
            StateRequest(
                runId = RunId("run-1"),
                threadId = ThreadId("thread-1"),
                config = FakeConfigProvider().current(),
                vars = emptyMap(),
            )
        return source.entries(req).single { it.key == InputStateSource.KEY_SHELL }.text
    }

    private object FakeAccessibility : AccessibilityStatus {
        override val connected: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private class FakeShell(
        state: ShellState,
        paired: Boolean,
    ) : ShellInput {
        override val state: StateFlow<ShellState> = MutableStateFlow(state)
        override val paired: StateFlow<Boolean> = MutableStateFlow(paired)

        override suspend fun pair(
            pairingPort: Int,
            pairCode: String,
        ): Outcome<Unit> = unused()

        override suspend fun connect(): Boolean = unused()

        override suspend fun tap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): Boolean = unused()

        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
        ): Boolean = unused()

        override suspend fun keyEvent(code: Int): Boolean = unused()

        override suspend fun forceStop(packageName: String): Boolean = unused()

        override suspend fun keyCombination(vararg codes: Int): Boolean = unused()

        private fun unused(): Nothing = error("ShellInput member is not used in this test")
    }
}
