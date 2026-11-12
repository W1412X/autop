/**
 * 熔断器（docs/design/tool_optimize/01 §7）：只保留"同一错误连续出现"检测。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.core.config.AgentBreakerSection
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CircuitBreakerTest {
    @Test
    fun `same error warns once then stops once`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        var verdict: BreakerVerdict = BreakerVerdict.None
        repeat(5) {
            verdict = breaker.observe(listOf(entry(ok = false, code = "device.not_editable")))
        }
        assertThat(verdict).isInstanceOf(BreakerVerdict.Stop::class.java)
        assertThat((verdict as BreakerVerdict.Stop).promptId).isEqualTo("agent.breaker.same_error_stop")
        // 同一级别只触发一次。
        assertThat(breaker.observe(listOf(entry(ok = false, code = "device.not_editable"))))
            .isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `same error warn threshold fires once`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        val verdicts = (1..4).map { breaker.observe(listOf(entry(ok = false, code = "device.action_failed"))) }
        assertThat(verdicts[2]).isInstanceOf(BreakerVerdict.Warn::class.java)
        assertThat(verdicts[0]).isEqualTo(BreakerVerdict.None)
        assertThat(verdicts[1]).isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `success resets the counter`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        repeat(2) { breaker.observe(listOf(entry(ok = false, code = "device.not_editable"))) }
        breaker.observe(listOf(entry(ok = true, code = null)))
        val verdicts = (1..3).map { breaker.observe(listOf(entry(ok = false, code = "device.not_editable"))) }
        assertThat(verdicts[0]).isEqualTo(BreakerVerdict.None)
        assertThat(verdicts[2]).isInstanceOf(BreakerVerdict.Warn::class.java)
    }

    @Test
    fun `different error code restarts counting`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        repeat(2) { breaker.observe(listOf(entry(ok = false, code = "device.not_editable"))) }
        val verdict = breaker.observe(listOf(entry(ok = false, code = "device.action_failed")))
        assertThat(verdict).isEqualTo(BreakerVerdict.None)
    }

    @Test
    fun `ten successful identical scroll calls never trip the breaker`() {
        val breaker = CircuitBreaker(AgentBreakerSection())
        repeat(10) {
            val verdict = breaker.observe(listOf(entry(tool = "tool.scroll", ok = true, code = null)))
            assertThat(verdict).isEqualTo(BreakerVerdict.None)
        }
    }

    private fun entry(
        tool: String = "tool.tap",
        ok: Boolean,
        code: String?,
    ): BreakerEntry = BreakerEntry(toolId = tool, ok = ok, errorCode = code)
}
