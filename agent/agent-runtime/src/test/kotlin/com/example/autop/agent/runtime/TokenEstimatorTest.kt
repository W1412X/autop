/**
 * token 估算（agent-harness-overhaul WP4.7）：工具描述计入估算。
 */
package com.example.autop.agent.runtime

import com.example.autop.core.config.AgentTokensSection
import com.example.autop.llm.ToolSchema
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun `tool description is counted`() {
        val estimator = TokenEstimator(AgentTokensSection())
        val schema =
            ToolSchema(name = "toolname", description = "descriptiontext", parameters = buildJsonObject { })

        val withTools = estimator.estimate(emptyList(), emptyList(), listOf(schema))
        val without = estimator.estimate(emptyList(), emptyList(), emptyList())

        val expected =
            estimator.estimateText(schema.name) +
                estimator.estimateText(schema.description) +
                estimator.estimateText(schema.parameters.toString())
        assertThat(withTools - without).isEqualTo(expected)
    }
}
