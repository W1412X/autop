/**
 * 熔断器（docs/tech/04 §3.7）：无法判断屏幕是否变化（盲操作）时按“未变化”计数，
 * 否则应用屏蔽节点树后重复动作永远触发不了 repeat/stuck。
 */
package com.example.autop.agent.runtime.guard

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

    /** 成功的 WRITE_SCREEN 调用；无 replay/observation = 前后指纹都取不到（盲操作）。 */
    private fun entry(args: String): BreakerEntry =
        BreakerEntry(
            toolId = "tool.tap",
            argsJson = args,
            access = ToolAccess.WRITE_SCREEN,
            output = ToolOutput(ok = true, content = emptyList()),
        )
}
