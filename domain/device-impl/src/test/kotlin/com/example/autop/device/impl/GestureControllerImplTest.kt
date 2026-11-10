/**
 * 动作执行（agent-harness-overhaul WP7.4）：ShellAttempt → InjectionResult 映射——
 * 没走 shell 的原因（shellSkip）随结果带给执行回执。
 */
package com.example.autop.device.impl

import com.example.autop.device.InjectionChannel
import com.example.autop.device.ShellSkip
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureControllerImplTest {
    @Test
    fun `not connected maps to accessibility with NOT_CONNECTED`() {
        val result = injectionResultOf(ShellAttempt.Skipped(ShellSkip.NOT_CONNECTED), accessibilityOk = true)

        assertThat(result.ok).isTrue()
        assertThat(result.channel).isEqualTo(InjectionChannel.ACCESSIBILITY)
        assertThat(result.shellSkip).isEqualTo(ShellSkip.NOT_CONNECTED)
    }
}
