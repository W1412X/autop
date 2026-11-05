/**
 * 04 §6 项 12：渲染规则——观测降级（keepFull 外换 stub）、图片保留（keep 外换 stub）、
 * 长文本截断（头尾保留 + 中间 stub）。断言发给模型的最终消息形态。
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

class RenderRulesTest {
    @Test
    fun `12a 观测降级：keepFull 外的观测换 stub`() =
        runTest {
            val tool =
                FakeTool { _, _ ->
                    counter += 1
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text("观测原文$counter")),
                        observation = observation("fp$counter", "页面 $counter"),
                    )
                }
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides = overridesOf("agent", "history", "observation" to mapOf("keepFull" to 2)),
                    fakes = HarnessFakes(rules = defaultRenderRules()),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val contents = lastToolContents(h)
            assertThat(contents).hasSize(3)
            // 最旧的第 1 个被 stub 替换；后两个保留原文
            assertThat(textOf(contents[0])).doesNotContain("观测原文1")
            assertThat(textOf(contents[1])).contains("观测原文2")
            assertThat(textOf(contents[2])).contains("观测原文3")
        }

    @Test
    fun `12b 图片保留：keep 外的图片换 stub`() =
        runTest {
            val tool =
                FakeTool { _, _ ->
                    counter += 1
                    ToolOutput(ok = true, content = listOf(image("shot$counter"), Block.Text("截图 $counter")))
                }
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides = overridesOf("agent", "history", "image" to mapOf("keep" to 1)),
                    fakes = HarnessFakes(rules = defaultRenderRules()),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val contents = lastToolContents(h)
            val images = contents.flatMap { c -> c.filterIsInstance<Block.Image>() }
            assertThat(images).hasSize(1)
            assertThat(images.single().fileRef).isEqualTo("shot3")
            // 被替换的位置是文本 stub
            assertThat(textOf(contents[0])).doesNotContain("shot1")
        }

    @Test
    fun `12c 长文本截断：保留头尾 中间换 stub`() =
        runTest {
            val longText = "头".repeat(10) + "中".repeat(280) + "尾".repeat(10)
            val tool =
                FakeTool { _, _ ->
                    ToolOutput(ok = true, content = listOf(Block.Text(longText)))
                }
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides =
                        overridesOf(
                            "agent",
                            "history",
                            "truncate" to mapOf("maxChars" to 100, "headChars" to 10, "tailChars" to 10),
                        ),
                    fakes = HarnessFakes(rules = defaultRenderRules()),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val contents = lastToolContents(h)
            val texts = contents.single().filterIsInstance<Block.Text>().map { it.text }
            assertThat(texts).hasSize(3)
            assertThat(texts[0]).isEqualTo("头".repeat(10))
            assertThat(texts[2]).isEqualTo("尾".repeat(10))
            assertThat(texts[1].length).isLessThan(longText.length)
        }

    @Test
    fun `12d 长文本不截断：maxChars 不正数全文保留`() =
        runTest {
            val longText = "头".repeat(10) + "中".repeat(280) + "尾".repeat(10)
            val tool =
                FakeTool { _, _ ->
                    ToolOutput(ok = true, content = listOf(Block.Text(longText)))
                }
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides =
                        overridesOf(
                            "agent",
                            "history",
                            "truncate" to mapOf("maxChars" to -1, "headChars" to 10, "tailChars" to 10),
                        ),
                    fakes = HarnessFakes(rules = defaultRenderRules()),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val texts = lastToolContents(h).single().filterIsInstance<Block.Text>().map { it.text }
            assertThat(texts).containsExactly(longText)
        }

    @Test
    fun `12e 观测不降级：keepFull 不正数全部保留原文`() =
        runTest {
            val tool =
                FakeTool { _, _ ->
                    counter += 1
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text("观测原文$counter")),
                        observation = observation("fp$counter", "页面 $counter"),
                    )
                }
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides = overridesOf("agent", "history", "observation" to mapOf("keepFull" to -1)),
                    fakes = HarnessFakes(rules = defaultRenderRules()),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val contents = lastToolContents(h)
            assertThat(textOf(contents[0])).contains("观测原文1")
            assertThat(textOf(contents[1])).contains("观测原文2")
            assertThat(textOf(contents[2])).contains("观测原文3")
        }

    private var counter = 0

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
        )

    private fun image(fileRef: String): Block.Image =
        Block.Image(fileRef = fileRef, mime = "image/png", width = 10, height = 10)

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
