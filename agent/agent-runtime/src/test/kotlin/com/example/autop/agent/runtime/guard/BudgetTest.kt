/**
 * [Budget] 单测（docs/tech/04 §3.7）：时长预算从构造时刻起算（N170 回归：
 * 真实时钟为 epoch 毫秒，startMillis 默认 0 时 `now - 0` 恒超上限），
 * 以及各维度 0/负值 = 不限的语义。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.ToolOutput
import com.example.autop.core.config.AgentBudgetSection
import com.example.autop.core.testing.FakeClock
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class BudgetTest {
    @Test
    fun `duration budget starts at construction time on wall clock epochs`() {
        val clock = FakeClock(EPOCH_NOW_MS)
        val budget = Budget(AgentBudgetSection(maxDurationMs = LIMIT_MS), clock)

        assertThat(budget.reason()).isNull()
        clock.advanceBy(LIMIT_MS - 1)
        assertThat(budget.reason()).isNull()
        clock.advanceBy(1)
        assertThat(budget.reason()).isEqualTo("duration")
    }

    @Test
    fun `explicit startMillis anchors the duration check`() {
        val clock = FakeClock(EPOCH_NOW_MS)
        val budget =
            Budget(
                AgentBudgetSection(maxDurationMs = LIMIT_MS),
                clock,
                startMillis = EPOCH_NOW_MS - LIMIT_MS + 1,
            )

        assertThat(budget.reason()).isNull()
        clock.advanceBy(1)
        assertThat(budget.reason()).isEqualTo("duration")
    }

    @Test
    fun `zero budget values mean unlimited for every dimension`() {
        val clock = FakeClock(EPOCH_NOW_MS)
        val budget = Budget(AgentBudgetSection(), clock)

        repeat(100) {
            budget.recordToolRound(listOf(screenshotOutput(), plainOutput()))
            budget.recordUsage(Usage(inputTokens = 10_000, outputTokens = 10_000), estimatedTokens = 10_000)
        }
        clock.advanceBy(24 * HOURS_MS)

        assertThat(budget.reason()).isNull()
        assertThat(budget.exhausted()).isFalse()
    }

    @Test
    fun `negative budget values behave as unlimited`() {
        val clock = FakeClock(EPOCH_NOW_MS)
        val budget =
            Budget(
                AgentBudgetSection(maxSteps = -1, maxTotalTokens = -1, maxDurationMs = -1, maxScreenshots = -1),
                clock,
            )

        budget.recordToolRound(listOf(screenshotOutput()))
        clock.advanceBy(HOURS_MS)

        assertThat(budget.reason()).isNull()
    }

    @Test
    fun `steps budget exhausts after maxSteps tool rounds`() {
        val budget = Budget(AgentBudgetSection(maxSteps = 2), FakeClock(EPOCH_NOW_MS))

        budget.recordToolRound(listOf(plainOutput()))
        assertThat(budget.reason()).isNull()
        budget.recordToolRound(listOf(plainOutput()))
        assertThat(budget.reason()).isEqualTo("steps")
    }

    @Test
    fun `tokens budget accumulates server usage over estimates`() {
        val budget = Budget(AgentBudgetSection(maxTotalTokens = 100), FakeClock(EPOCH_NOW_MS))

        budget.recordUsage(Usage(inputTokens = 60, outputTokens = 50), estimatedTokens = 0)
        assertThat(budget.reason()).isEqualTo("tokens")
    }

    @Test
    fun `tokens budget falls back to estimate without server usage`() {
        val budget = Budget(AgentBudgetSection(maxTotalTokens = 100), FakeClock(EPOCH_NOW_MS))

        budget.recordUsage(null, estimatedTokens = 99)
        assertThat(budget.reason()).isNull()
        budget.recordUsage(null, estimatedTokens = 1)
        assertThat(budget.reason()).isEqualTo("tokens")
    }

    @Test
    fun `screenshots budget counts outputs tagged screenshot true`() {
        val budget = Budget(AgentBudgetSection(maxScreenshots = 1), FakeClock(EPOCH_NOW_MS))

        budget.recordToolRound(listOf(plainOutput()))
        assertThat(budget.reason()).isNull()
        budget.recordToolRound(listOf(plainOutput(), screenshotOutput()))
        assertThat(budget.reason()).isEqualTo("screenshots")
    }

    private fun screenshotOutput(): ToolOutput =
        ToolOutput(ok = true, content = emptyList(), data = buildJsonObject { put("screenshot", true) })

    private fun plainOutput(): ToolOutput =
        ToolOutput(ok = true, content = emptyList(), data = buildJsonObject { put("screenshot", false) })
}

/** 真实时钟量级（epoch 毫秒，约 2026-10）。 */
private const val EPOCH_NOW_MS: Long = 1_790_000_000_000L

/** 15 分钟上限（N170 事发值）。 */
private const val LIMIT_MS: Long = 900_000L

private const val HOURS_MS: Long = 3_600_000L
