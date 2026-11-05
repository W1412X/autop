/**
 * 04 §6 项 16（R1/B1）：连续三次差量观测且 keepFull=2 时，窗口最旧的差量渲染为
 * fullBlocks 完整原文、最新的保留差量；fullBlocks 缺失时出现 diff_base_missing 提示。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.ObservationRef
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.HarnessFakes
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.defaultRenderRules
import com.example.autop.agent.runtime.testing.overridesOf
import com.example.autop.llm.Block
import com.example.autop.llm.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiffBaseTest {
    @Test
    fun `16a 窗口最旧的差量渲染 fullBlocks 最新的保留差量`() =
        runTest {
            val tool =
                FakeTool { _, _ ->
                    counter += 1
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text("差量$counter")),
                        observation = observation("fp$counter", "页面 $counter"),
                        fullContent = listOf(Block.Text("完整原文$counter")),
                    )
                }
            val h = harness(tool)

            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val contents = lastToolContents(h)
            assertThat(contents).hasSize(3)
            // 窗口外的最旧差量被 stub 替换
            assertThat(textOf(contents[0])).doesNotContain("差量1")
            assertThat(textOf(contents[0])).doesNotContain("完整原文1")
            // 窗口内最旧的差量换成 fullBlocks 完整原文
            assertThat(textOf(contents[1])).contains("完整原文2")
            assertThat(textOf(contents[1])).doesNotContain("差量2")
            // 最新的差量原样保留
            assertThat(textOf(contents[2])).contains("差量3")
        }

    @Test
    fun `16b fullBlocks 为空时出现 diff_base_missing 提示`() =
        runTest {
            val tool =
                FakeTool { _, _ ->
                    counter += 1
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text("差量$counter")),
                        observation = observation("fp$counter", "页面 $counter"),
                    )
                }
            val h = harness(tool)

            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val contents = lastToolContents(h)
            val texts = contents[1].filterIsInstance<Block.Text>().map { it.text }
            // 提示在前、原差量在后
            assertThat(texts).hasSize(2)
            assertThat(texts[0]).contains("完整屏幕原文不可用")
            assertThat(texts[1]).isEqualTo("差量2")
        }

    private var counter = 0

    private fun harness(tool: FakeTool): RuntimeHarness =
        RuntimeHarness(
            tools = setOf(tool),
            configOverrides = overridesOf("agent", "history", "observation" to mapOf("keepFull" to 2)),
            fakes = HarnessFakes(rules = defaultRenderRules()),
        )

    private fun observation(
        fingerprint: String,
        summary: String,
    ): ObservationRef =
        ObservationRef(
            fingerprint = fingerprint,
            packageName = "com.example",
            activity = ".Main",
            summaryLine = summary,
            nodeCount = 10,
            hasImage = false,
            isDiff = true,
        )

    private fun lastToolContents(h: RuntimeHarness): List<List<Block>> =
        h.llm.requests
            .last()
            .messages
            .filter { it.role == Role.TOOL }
            .flatMap { it.blocks }
            .map { (it as Block.ToolResult).content }

    private fun textOf(blocks: List<Block>): String =
        blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
}
