/**
 * 熔断器（docs/tech/04 §3.7）：完全盲操作（无任何指纹）时按“未变化”计数，
 * 否则重复动作永远触发不了 repeat/stuck；但有观测指纹时按同源连续指纹判断，
 * 应用屏蔽节点树（无 replay）但 visual 观测一直在变的场景不能被误熔断（N170 审计）。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.ObservationRef
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolOutput
import com.example.autop.core.config.AgentBreakerSection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CircuitBreakerTest {
    @Test
    fun `same args in blind mode still reaches repeat warning`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(3) {
            verdict = breaker.observe(listOf(entry(args = """{"x":30,"y":60}""")))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat((verdict as BreakerVerdict.Warn).promptId).isEqualTo("agent.breaker.repeat_warn")
    }

    @Test
    fun `different args in blind mode still reaches stuck warning`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(3) { index ->
            verdict = breaker.observe(listOf(entry(args = """{"x":$index}""")))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat((verdict as BreakerVerdict.Warn).promptId).isEqualTo("agent.breaker.stuck_warn")
    }

    @Test
    fun `visual fingerprints changing avoids false stuck stop without replay`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        // 微信屏蔽节点树：无 replay，但每次 visual 观测指纹都不同（屏幕其实一直在变）。
        repeat(8) { index ->
            verdict = breaker.observe(listOf(observedEntry(args = """{"x":$index}""", fingerprint = "fp$index")))
        }
        assertThat(verdict).isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `identical visual fingerprint still reaches stuck stop`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(6) { index ->
            verdict = breaker.observe(listOf(observedEntry(args = """{"x":$index}""", fingerprint = "same")))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Stop::class.java)
        assertThat((verdict as BreakerVerdict.Stop).promptId).isEqualTo("agent.breaker.stuck_stop")
    }

    @Test
    fun `read screen observation change resets stuck count`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        breaker.observe(listOf(observedEntry(args = """{"x":0}""", fingerprint = "a")))
        breaker.observe(listOf(observedEntry(args = """{"x":1}""", fingerprint = "a")))
        val warn = breaker.observe(listOf(observedEntry(args = """{"x":2}""", fingerprint = "a")))
        assertThat(warn).isInstanceOf(BreakerVerdict.Warn::class.java)
        // READ_SCREEN 观测到屏幕变化（a→b）应清零原地打转计数。
        breaker.observe(
            listOf(observedEntry(args = "{}", fingerprint = "b", access = ToolAccess.READ_SCREEN)),
        )
        breaker.observe(listOf(observedEntry(args = """{"x":3}""", fingerprint = "b")))
        breaker.observe(listOf(observedEntry(args = """{"x":4}""", fingerprint = "b")))
        val afterReset = breaker.observe(listOf(observedEntry(args = """{"x":5}""", fingerprint = "b")))
        // 清零后重新累计到 3 只是 Warn；若未清零则早已累计到 6 触发 Stop。
        assertThat(afterReset).isInstanceOf(BreakerVerdict.Warn::class.java)
    }

    /** 成功的 WRITE_SCREEN 调用；无 replay/observation = 前后指纹都取不到（盲操作）。 */
    private fun entry(args: String): BreakerEntry =
        BreakerEntry(
            toolId = "tool.tap",
            argsJson = args,
            access = ToolAccess.WRITE_SCREEN,
            output = ToolOutput(ok = true, content = emptyList()),
        )

    /** 带观测指纹的成功调用（默认 WRITE_SCREEN）；无 replay，模拟应用屏蔽节点树后的 visual 观测。 */
    private fun observedEntry(
        args: String,
        fingerprint: String,
        access: ToolAccess = ToolAccess.WRITE_SCREEN,
    ): BreakerEntry =
        BreakerEntry(
            toolId = if (access == ToolAccess.READ_SCREEN) "tool.observe" else "tool.tap",
            argsJson = args,
            access = access,
            output =
                ToolOutput(
                    ok = true,
                    content = emptyList(),
                    observation =
                        ObservationRef(
                            fingerprint = fingerprint,
                            packageName = "com.tencent.mm",
                            activity = "LauncherUI",
                            summaryLine = "com.tencent.mm / LauncherUI",
                            nodeCount = 30,
                            hasImage = false,
                        ),
                ),
        )
}
