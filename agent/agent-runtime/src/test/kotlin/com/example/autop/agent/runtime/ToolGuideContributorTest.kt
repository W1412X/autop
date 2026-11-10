/**
 * `context.tool_guide` 的省略/截断说明随配置渲染（agent-harness-overhaul WP3.9）：
 * 规则未启用或参数关闭时不输出对应段落。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ContextRequest
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ToolGuideContributorTest {
    @Test
    fun `default config only renders the image omission sentence`() =
        runTest {
            val contributor = ToolGuideContributor(TestPromptRepository())

            val text = checkNotNull(contributor.contribute(request(FakeConfigProvider()))).text

            assertThat(text).contains("较早的截图会被省略")
            assertThat(text).doesNotContain("较早的屏幕观测会被省略")
            assertThat(text).doesNotContain("超长的工具输出只保留开头和结尾")
        }

    @Test
    fun `keepFull positive enables omission sentence`() =
        runTest {
            val provider =
                FakeConfigProvider(
                    Json
                        .parseToJsonElement("""{"agent":{"history":{"observation":{"keepFull":2}}}}""")
                        .jsonObject,
                )
            val contributor = ToolGuideContributor(TestPromptRepository())

            val text = checkNotNull(contributor.contribute(request(provider))).text

            assertThat(text).contains("较早的屏幕观测会被省略")
        }

    private fun request(provider: FakeConfigProvider): ContextRequest =
        ContextRequest(
            runId = RunId("run-1"),
            threadId = ThreadId("thread-1"),
            config = provider.current(),
            goal = null,
            vars = emptyMap(),
        )
}
