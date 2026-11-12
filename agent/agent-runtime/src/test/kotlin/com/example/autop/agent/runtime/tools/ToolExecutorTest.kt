/**
 * 失败结果统一格式（agent-harness-overhaul WP7.14）：正文改写为
 * `agent.tool.error_result`，图片块保留，失败无错误时原样返回。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.RunRequest
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.runtime.RuntimeToolContext
import com.example.autop.agent.runtime.testing.FakePolicyEngine
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.NoopLogger
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Capability
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ToolExecutorTest {
    @Test
    fun `device style error is normalized to error_result with code`() =
        runTest {
            val tool =
                ScriptedTool("tool.t1", "t1") {
                    ToolOutput(
                        ok = false,
                        content = listOf(Block.Text("device style message")),
                        error =
                            AppError(
                                code = "device.action_failed",
                                message = "device style message",
                                hint = "换个方式",
                            ),
                    )
                }
            val result = executor(tool).runOne(call("t1"), ctx())

            val text = (result.output.content.single() as Block.Text).text
            assertThat(text).contains("device.action_failed")
            assertThat(text).contains("device style message")
            assertThat(text).contains("换个方式")
        }

    @Test
    fun `image blocks survive normalization`() =
        runTest {
            val tool =
                ScriptedTool("tool.t2", "t2") {
                    ToolOutput(
                        ok = false,
                        content = listOf(Block.Text("raw"), Block.Image("ref", "image/png", 1, 1)),
                        error = AppError(code = "device.screenshot_failed", message = "shot failed"),
                    )
                }
            val result = executor(tool).runOne(call("t2"), ctx())

            assertThat(result.output.content.filterIsInstance<Block.Image>()).hasSize(1)
            assertThat(result.output.content.filterIsInstance<Block.Text>()).hasSize(1)
        }

    @Test
    fun `ask_user error is normalized`() =
        runTest {
            val tool =
                ScriptedTool("tool.ask_user", "ask_user") {
                    ToolOutput(
                        ok = false,
                        content = emptyList(),
                        error = AppError(code = "agent.ask_timeout", message = "user did not answer"),
                    )
                }
            val result = executor(tool).runOne(call("ask_user"), ctx())

            val text = (result.output.content.single() as Block.Text).text
            assertThat(text).contains("agent.ask_timeout")
        }

    private fun call(name: String): Block.ToolCall = Block.ToolCall(id = "c-$name", name = name, argsJson = "{}")

    private fun executor(tool: Tool): ToolExecutor {
        val prompts = TestPromptRepository()
        return ToolExecutor(listOf(tool), FakePolicyEngine(), null, prompts, FakeClock(1_000L), NoopLogger)
    }

    private fun ctx(): ToolContext {
        val req =
            RunRequest(
                runId = RunId("run-1"),
                threadId = ThreadId("t1"),
                input = emptyList(),
                goal = null,
                vars = emptyMap(),
                config = FakeConfigProvider().current(),
                toolFilter = null,
            )
        return RuntimeToolContext(req, InMemoryThreadStore(FakeClock(1_000L)), { _, _, _ -> noUser() }, {})
    }

    private fun noUser(): Outcome<String> = Outcome.Err(AppError("test.unused", "unused"))

    private class ScriptedTool(
        override val id: String,
        name: String,
        private val result: () -> ToolOutput,
    ) : Tool {
        override val spec: ToolSpec = toolSpec(name = name, descriptionPromptId = "tool.fake") { }
        override val access: ToolAccess = ToolAccess.READ_MEMORY
        override val requiredCapabilities: Set<Capability> = emptySet()

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput = result()
    }
}
