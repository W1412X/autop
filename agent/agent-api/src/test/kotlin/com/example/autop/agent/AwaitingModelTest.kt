/**
 * 等待模型首个输出的判定（[nextAwaitingModel]）：进入采样为真，首个增量或状态离开采样为假，
 * 采样重试重新等待。对话页、控制条与悬浮球共用这一口径。
 */
package com.example.autop.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AwaitingModelTest {
    private fun fold(vararg events: AgentEvent): Boolean = events.fold(false) { acc, e -> nextAwaitingModel(acc, e) }

    @Test
    fun `entering sampling waits for the first output`() {
        assertThat(fold(AgentEvent.StateChanged(RunState.SAMPLING))).isTrue()
    }

    @Test
    fun `first text delta ends the wait`() {
        assertThat(fold(AgentEvent.StateChanged(RunState.SAMPLING), AgentEvent.TextDelta("你"))).isFalse()
    }

    @Test
    fun `thinking delta and tool call start end the wait`() {
        assertThat(fold(AgentEvent.StateChanged(RunState.SAMPLING), AgentEvent.ThinkingDelta("想"))).isFalse()
        assertThat(
            fold(AgentEvent.StateChanged(RunState.SAMPLING), AgentEvent.ToolCallStreaming(0, "tap", "c1")),
        ).isFalse()
    }

    @Test
    fun `leaving sampling for tools clears the wait`() {
        assertThat(
            fold(AgentEvent.StateChanged(RunState.SAMPLING), AgentEvent.StateChanged(RunState.EXECUTING_TOOLS)),
        ).isFalse()
    }

    @Test
    fun `next model request after tools waits again`() {
        assertThat(
            fold(
                AgentEvent.StateChanged(RunState.SAMPLING),
                AgentEvent.TextDelta("好"),
                AgentEvent.StateChanged(RunState.EXECUTING_TOOLS),
                AgentEvent.StateChanged(RunState.SAMPLING),
            ),
        ).isTrue()
    }

    @Test
    fun `sample retry waits again`() {
        assertThat(
            fold(
                AgentEvent.StateChanged(RunState.SAMPLING),
                AgentEvent.TextDelta("断"),
                AgentEvent.SampleRetrying(attempt = 1, code = "llm.stream_broken"),
            ),
        ).isTrue()
    }

    @Test
    fun `unrelated events keep the current value`() {
        assertThat(fold(AgentEvent.StateChanged(RunState.SAMPLING), AgentEvent.ToolProgress("c1", "x"))).isTrue()
    }
}
