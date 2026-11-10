/**
 * 批次内跳过与串行语义（agent-harness-overhaul WP5.5）：
 * 控制之后全跳过、写类失败后跳过非只读、只读照常执行、READ_SCREEN 串行、beforeEach 逐调用。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolControl
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.runtime.RuntimeToolContext
import com.example.autop.agent.runtime.testing.FakePolicyEngine
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.Capability
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ToolRouterTest {
    @Test
    fun `calls after finish are skipped with after_control`() =
        runTest {
            val after = ScriptedScreenTool("tool.after", "after") { okOutput() }
            val finish = ScriptedTool("tool.finish", "finish", ToolAccess.CONTROL) { finishOutput() }
            val tools = listOf(finish, after)
            val outputs =
                router(tools)
                    .execute(
                        calls = listOf(call("finish"), call("after")),
                        ctx = ctx(),
                        emitResult = {},
                        emitInjected = {},
                    )

            assertThat(outputs[0].control).isNotNull()
            assertThat(outputs[1].ok).isFalse()
            assertThat(outputs[1].error?.code).isEqualTo(AgentErrorCodes.SKIPPED_AFTER_CONTROL)
        }

    @Test
    fun `calls after policy pause are skipped`() =
        runTest {
            val pause =
                ScriptedTool("tool.pause", "pause", ToolAccess.CONTROL) {
                    ToolOutput(
                        ok = true,
                        content = emptyList(),
                        control = ToolControl.Pause(PauseReason.POLICY_BLOCKED, null),
                    )
                }
            val after = ScriptedTool("tool.after", "after", ToolAccess.READ_MEMORY) { okOutput() }
            val outputs =
                router(listOf(pause, after))
                    .execute(
                        calls = listOf(call("pause"), call("after")),
                        ctx = ctx(),
                        emitResult = {},
                        emitInjected = {},
                    )

            assertThat(outputs[1].error?.code).isEqualTo(AgentErrorCodes.SKIPPED_AFTER_CONTROL)
        }

    @Test
    fun `memory write after failed tap is skipped`() =
        runTest {
            val tap = ScriptedScreenTool("tool.tap", "tap") { failOutput() }
            val write = ScriptedTool("tool.memory_write", "memory_write", ToolAccess.WRITE_MEMORY) { okOutput() }
            val outputs =
                router(listOf(tap, write))
                    .execute(
                        calls = listOf(call("tap"), call("memory_write")),
                        ctx = ctx(),
                        emitResult = {},
                        emitInjected = {},
                    )

            assertThat(outputs[1].error?.code).isEqualTo(AgentErrorCodes.SKIPPED_PREVIOUS_FAILED)
        }

    @Test
    fun `reads after failed tap still run`() =
        runTest {
            val tap = ScriptedScreenTool("tool.tap", "tap") { failOutput() }
            val read = ScriptedTool("tool.memory_list", "memory_list", ToolAccess.READ_MEMORY) { okOutput() }
            val outputs =
                router(listOf(tap, read))
                    .execute(
                        calls = listOf(call("tap"), call("memory_list")),
                        ctx = ctx(),
                        emitResult = {},
                        emitInjected = {},
                    )

            assertThat(outputs[1].ok).isTrue()
        }

    @Test
    fun `finish after failed tap is skipped`() =
        runTest {
            val tap = ScriptedScreenTool("tool.tap", "tap") { failOutput() }
            val finish = ScriptedTool("tool.finish", "finish", ToolAccess.CONTROL) { finishOutput() }
            val outputs =
                router(listOf(tap, finish))
                    .execute(
                        calls = listOf(call("tap"), call("finish")),
                        ctx = ctx(),
                        emitResult = {},
                        emitInjected = {},
                    )

            assertThat(outputs[1].error?.code).isEqualTo(AgentErrorCodes.SKIPPED_PREVIOUS_FAILED)
        }

    @Test
    fun `ask_user timeout does not skip later calls`() =
        runTest {
            val ask =
                ScriptedTool("tool.ask_user", "ask_user", ToolAccess.CONTROL) {
                    ToolOutput(
                        ok = false,
                        content = emptyList(),
                        error =
                            com.example.autop.core.common
                                .AppError(AgentErrorCodes.ASK_TIMEOUT, "timeout"),
                    )
                }
            val after = ScriptedScreenTool("tool.after", "after") { okOutput() }
            val outputs =
                router(listOf(ask, after))
                    .execute(
                        calls = listOf(call("ask_user"), call("after")),
                        ctx = ctx(),
                        emitResult = {},
                        emitInjected = {},
                    )

            assertThat(outputs[1].ok).isTrue()
        }

    @Test
    fun `observe calls run sequentially`() =
        runTest {
            var active = 0
            var maxActive = 0
            val observe =
                ScriptedTool("tool.observe", "observe", ToolAccess.READ_SCREEN) {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                    delay(5)
                    active -= 1
                    okOutput()
                }
            val tools = listOf(observe)
            router(tools)
                .execute(
                    calls = listOf(call("observe"), call("observe"), call("observe")),
                    ctx = ctx(),
                    emitResult = {},
                    emitInjected = {},
                )

            assertThat(maxActive).isEqualTo(1)
        }

    @Test
    fun `beforeEach is awaited before every call`() =
        runTest {
            var beforeEachCount = 0
            val read =
                ScriptedTool("tool.memory_list", "memory_list", ToolAccess.READ_MEMORY) {
                    assertThat(beforeEachCount).isGreaterThan(0)
                    okOutput()
                }
            val write =
                ScriptedScreenTool("tool.tap", "tap") {
                    assertThat(beforeEachCount).isGreaterThan(0)
                    okOutput()
                }
            router(listOf(write, read))
                .execute(
                    calls = listOf(call("tap"), call("memory_list")),
                    ctx = ctx(),
                    emitResult = {},
                    emitInjected = {},
                    beforeEach = { beforeEachCount += 1 },
                )

            // 串行段每调用一次；并行段（两个 READ_MEMORY）在段开始前共一次。
            assertThat(beforeEachCount).isEqualTo(2)
        }

    private fun call(name: String): Block.ToolCall = Block.ToolCall(id = "c-$name", name = name, argsJson = "{}")

    private fun okOutput(): ToolOutput = ToolOutput(ok = true, content = listOf(Block.Text("ok")))

    private fun failOutput(): ToolOutput =
        ToolOutput(
            ok = false,
            content = emptyList(),
            error =
                com.example.autop.core.common
                    .AppError("device.action_failed", "failed"),
        )

    private fun finishOutput(): ToolOutput =
        ToolOutput(ok = true, content = emptyList(), control = ToolControl.Finish(RunOutcome.SUCCEEDED, "done", null))

    private fun router(tools: List<Tool>): ToolRouter {
        val prompts = TestPromptRepository()
        val clock = FakeClock(1_000L)
        val executor = ToolExecutor(tools, FakePolicyEngine(), null, prompts, clock)
        return ToolRouter(tools, executor, emptyList(), prompts)
    }

    private fun ctx(): ToolContext {
        val config = FakeConfigProvider().current()
        val req =
            RunRequest(
                runId = RunId("run-1"),
                threadId =
                    com.example.autop.core.common
                        .ThreadId("t1"),
                input = emptyList(),
                goal = null,
                vars = emptyMap(),
                config = config,
                toolFilter = null,
            )
        return RuntimeToolContext(
            req = req,
            threadStore = InMemoryThreadStore(FakeClock(1_000L)),
            ask = { _, _, _ ->
                com.example.autop.agent.runtime
                    .noUserInteraction()
            },
            progress = {},
        )
    }

    private class ScriptedScreenTool(
        override val id: String,
        name: String,
        private val result: suspend () -> ToolOutput,
    ) : com.example.autop.agent.ScreenActionTool {
        override val spec: ToolSpec = toolSpec(name = name, descriptionPromptId = "tool.fake") { }
        override val access: ToolAccess = ToolAccess.WRITE_SCREEN
        override val requiredCapabilities: Set<Capability> = emptySet()
        override val requiresModelVision: Boolean = false

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<com.example.autop.agent.PreparedAction> =
            Outcome.Ok(
                com.example.autop.agent
                    .PreparedAction(
                        com.example.autop.policy
                            .PolicySubject(toolId = id),
                        Unit,
                    ),
            )

        override suspend fun perform(
            action: com.example.autop.agent.PreparedAction,
            ctx: ToolContext,
        ): ToolOutput = result()
    }

    private class ScriptedTool(
        override val id: String,
        name: String,
        override val access: ToolAccess,
        private val result: suspend () -> ToolOutput,
    ) : Tool {
        override val spec: ToolSpec = toolSpec(name = name, descriptionPromptId = "tool.fake") { }
        override val requiredCapabilities: Set<Capability> = emptySet()
        override val requiresModelVision: Boolean = false

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput = result()
    }
}
