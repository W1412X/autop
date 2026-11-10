/**
 * 熔断器（agent-harness-overhaul WP2.11）：重复动作、同错误、原地打转各自计数，
 * 只看工具收尾的 ScreenDelta；UNKNOWN/null 按“未变化”计。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.ScreenDelta
import com.example.autop.agent.ScreenDeltaKind
import com.example.autop.agent.ScreenDeltaReasons
import com.example.autop.agent.ToolAccess
import com.example.autop.core.config.AgentBreakerSection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CircuitBreakerTest {
    @Test
    fun `three unchanged taps warn once`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(3) { index ->
            verdict = breaker.observe(listOf(entry(args = """{"x":$index}""", delta = unchanged())))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat((verdict as BreakerVerdict.Warn).promptId).isEqualTo("agent.breaker.stuck_warn")
        // 同一级别只触发一次。
        assertThat(breaker.observe(listOf(entry(args = """{"x":9}""", delta = unchanged()))))
            .isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `six unchanged taps stop`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(6) { index ->
            verdict = breaker.observe(listOf(entry(args = """{"x":$index}""", delta = unchanged())))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Stop::class.java)
        assertThat((verdict as BreakerVerdict.Stop).promptId).isEqualTo("agent.breaker.stuck_stop")
    }

    @Test
    fun `visual changed delta resets stuck`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        breaker.observe(listOf(entry(args = """{"x":0}""", delta = unchanged())))
        breaker.observe(listOf(entry(args = """{"x":1}""", delta = unchanged())))
        // 视觉收尾给出 CHANGED：清零原地打转。
        breaker.observe(listOf(entry(args = """{"x":2}""", delta = changed())))
        val verdict = breaker.observe(listOf(entry(args = """{"x":3}""", delta = unchanged())))
        breaker.observe(listOf(entry(args = """{"x":4}""", delta = unchanged())))
        assertThat(verdict).isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `unknown delta counts as unchanged`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(3) { index ->
            verdict = breaker.observe(listOf(entry(args = """{"x":$index}""", delta = unknown())))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat((verdict as BreakerVerdict.Warn).promptId).isEqualTo("agent.breaker.stuck_warn")
    }

    @Test
    fun `observe with CHANGED resets stuck count`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        breaker.observe(listOf(entry(args = """{"x":0}""", delta = unchanged())))
        breaker.observe(listOf(entry(args = """{"x":1}""", delta = unchanged())))
        // READ_SCREEN（observe）观测到变化同样清零。
        breaker.observe(listOf(entry(args = "{}", access = ToolAccess.READ_SCREEN, delta = changed())))
        val verdict = breaker.observe(listOf(entry(args = """{"x":2}""", delta = unchanged())))
        breaker.observe(listOf(entry(args = """{"x":3}""", delta = unchanged())))
        assertThat(verdict).isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `same error warn then stop fires once each`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        val verdicts =
            (1..6).map {
                breaker.observe(
                    listOf(entry(args = """{"x":$it}""", ok = false, errorCode = "device.action_failed")),
                )
            }
        assertThat(verdicts[2]).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat((verdicts[2] as BreakerVerdict.Warn).promptId).isEqualTo("agent.breaker.same_error_warn")
        assertThat(verdicts[3]).isEqualTo(BreakerVerdict.None)
        assertThat(verdicts[4]).isInstanceOf(BreakerVerdict.Stop::class.java)
        assertThat((verdicts[4] as BreakerVerdict.Stop).promptId).isEqualTo("agent.breaker.same_error_stop")
        assertThat(verdicts[5]).isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `repeat reaches stop`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(5) {
            verdict =
                breaker.observe(
                    listOf(entry(args = SAME_ARGS, access = ToolAccess.WRITE_MEMORY, delta = unchanged())),
                )
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Stop::class.java)
        assertThat((verdict as BreakerVerdict.Stop).promptId).isEqualTo("agent.breaker.repeat_stop")
        assertThat(verdict.vars["count"]).isEqualTo("5")
    }

    @Test
    fun `warn fires again after reset`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        repeat(3) { index ->
            breaker.observe(listOf(entry(args = """{"x":$index}""", delta = unchanged())))
        }
        breaker.observe(listOf(entry(args = """{"x":3}""", delta = changed())))
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(3) { index ->
            verdict = breaker.observe(listOf(entry(args = """{"x":${index + 4}}""", delta = unchanged())))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat((verdict as BreakerVerdict.Warn).promptId).isEqualTo("agent.breaker.stuck_warn")
    }

    private fun changed(): ScreenDelta = ScreenDelta(ScreenDeltaKind.CHANGED, ScreenDeltaReasons.CONTENT)

    private fun unchanged(): ScreenDelta = ScreenDelta(ScreenDeltaKind.UNCHANGED, ScreenDeltaReasons.SAME)

    private fun unknown(): ScreenDelta = ScreenDelta(ScreenDeltaKind.UNKNOWN, ScreenDeltaReasons.NO_BASELINE)

    private fun entry(
        args: String,
        access: ToolAccess = ToolAccess.WRITE_SCREEN,
        ok: Boolean = true,
        errorCode: String? = null,
        delta: ScreenDelta? = null,
    ): BreakerEntry =
        BreakerEntry(
            toolId = if (access == ToolAccess.WRITE_MEMORY) "tool.memory_write" else "tool.tap",
            argsJson = args,
            access = access,
            ok = ok,
            errorCode = errorCode,
            screenDelta = delta,
        )

    private companion object {
        const val SAME_ARGS = """{"x":50,"y":50}"""
    }
}
