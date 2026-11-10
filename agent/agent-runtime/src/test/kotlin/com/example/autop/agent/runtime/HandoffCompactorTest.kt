/**
 * 交接压缩（docs/tech/04 §3.6）：无模型报 NOT_CONFIGURED、LIGHT→MAIN 回退、
 * 成功摘要装配 Baseline、请求超长从 live 开头删旧重试（ToolCall 连同 TOOL 一起删）、
 * 只剩 MIN_LIVE 条时不再删旧。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.CompactionInput
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.RenderItem
import com.example.autop.agent.runtime.compaction.HandoffCompactor
import com.example.autop.agent.runtime.testing.FakeLlmGateway
import com.example.autop.agent.runtime.testing.ScriptedLlmProvider
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.ModelRole
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HandoffCompactorTest {
    @Test
    fun `无模型时返回 NOT_CONFIGURED`() =
        runTest {
            val compactor = HandoffCompactor(EmptyGateway, TestPromptRepository())

            val result = compactor.compact(input(live = listOf(render(1))))

            val err = (result as Outcome.Err).error
            assertThat(err.code).isEqualTo(LlmErrorCodes.NOT_CONFIGURED)
        }

    @Test
    fun `LIGHT 未配置时回退 MAIN`() =
        runTest {
            val llm = ScriptedLlmProvider().say("交接摘要")
            val compactor = HandoffCompactor(MainOnlyGateway(llm), TestPromptRepository())

            val result = compactor.compact(input(live = listOf(render(1))))

            val value = (result as Outcome.Ok).value
            assertThat(value.summaryText).isEqualTo("交接摘要")
        }

    @Test
    fun `成功摘要装配 Baseline 并传交接模板`() =
        runTest {
            val llm = ScriptedLlmProvider().say("交接摘要")
            val compactor = HandoffCompactor(FakeLlmGateway(llm), TestPromptRepository())
            val live = listOf(render(1), render(2), render(3))
            val in0 =
                input(
                    live = live,
                    userMessages =
                        listOf(
                            2L to Msg(Role.USER, listOf(Block.Text("用户二"))),
                            1L to Msg(Role.USER, listOf(Block.Text("用户一"))),
                        ),
                    plan = listOf(PlanItem("第一步", PlanStatus.DONE)),
                    runVars = mapOf("trigger.source" to "通知"),
                )

            val result = (compactor.compact(in0) as Outcome.Ok).value

            assertThat(result.summaryText).isEqualTo("交接摘要")
            assertThat(result.coveredFromSeq).isEqualTo(1L)
            assertThat(result.coveredToSeq).isEqualTo(3L)
            // Baseline = 预算内用户消息 + 摘要包装 + 最后观测
            assertThat(result.baselineMessages).hasSize(3)
            assertThat((result.baselineMessages[0].blocks[0] as Block.Text).text).isEqualTo("用户一")
            assertThat((result.baselineMessages[1].blocks[0] as Block.Text).text).isEqualTo("用户二")
            val wrapper = (result.baselineMessages[2].blocks[0] as Block.Text).text
            assertThat(wrapper).contains("交接摘要")
            assertThat(wrapper).contains("第一步")
            assertThat(wrapper).contains("通知")
            // 请求 = system + live + 交接说明，tools=[]
            val request = llm.requests.single()
            assertThat(request.tools).isEmpty()
            assertThat(request.system).hasSize(1)
            assertThat(request.messages).hasSize(4)
            val handoff = (request.messages.last().blocks[0] as Block.Text).text
            assertThat(handoff).contains("交接")
        }

    @Test
    fun `请求超长时从 live 开头删旧重试`() =
        runTest {
            val llm =
                ScriptedLlmProvider()
                    .fail(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = "too long"))
                    .say("第二次摘要")
            val compactor = HandoffCompactor(FakeLlmGateway(llm), TestPromptRepository())
            // 首条是带 ToolCall 的 ASSISTANT，删旧时连同其后的 TOOL 一起删
            val live =
                listOf(
                    RenderItem(1, Msg(Role.ASSISTANT, listOf(Block.ToolCall("c1", "tap", "{}"))), null, null),
                    RenderItem(
                        2,
                        Msg(Role.TOOL, listOf(Block.ToolResult("c1", listOf(Block.Text("ok")), false))),
                        null,
                        "tool.tap",
                    ),
                    render(3),
                    render(4),
                )

            val result = (compactor.compact(input(live = live)) as Outcome.Ok).value

            assertThat(result.summaryText).isEqualTo("第二次摘要")
            assertThat(llm.requests).hasSize(2)
            // 第二次请求删掉了 2 条旧消息：4 条 live + 1 条交接说明 → 3 条
            assertThat(llm.requests[1].messages).hasSize(3)
        }

    @Test
    fun `只剩 MIN_LIVE 条时不再删旧`() =
        runTest {
            val llm = ScriptedLlmProvider().fail(AppError(code = LlmErrorCodes.CONTEXT_EXCEEDED, message = "too long"))
            val compactor = HandoffCompactor(FakeLlmGateway(llm), TestPromptRepository())

            val result = compactor.compact(input(live = listOf(render(1), render(2))))

            val err = (result as Outcome.Err).error
            assertThat(err.code).isEqualTo(LlmErrorCodes.CONTEXT_EXCEEDED)
            assertThat(llm.requests).hasSize(1)
        }

    @Test
    fun `baseline order is initial context, user messages, summary, notes, last observation`() =
        runTest {
            val llm = ScriptedLlmProvider().say("交接摘要")
            val compactor = HandoffCompactor(FakeLlmGateway(llm), TestPromptRepository())
            val observation =
                RenderItem(
                    2,
                    Msg(Role.USER, listOf(Block.Text("观测文本"))),
                    null,
                    null,
                    fullBlocks = listOf(Block.Text("观测文本")),
                )
            val in0 =
                input(
                    live = listOf(render(1), observation),
                    userMessages = listOf(1L to Msg(Role.USER, listOf(Block.Text("用户一")))),
                    initialContext = "goal: 记账",
                    notes = listOf("用户名是小明"),
                    lastObservation = observation,
                )

            val result = (compactor.compact(in0) as Outcome.Ok).value

            val texts = result.baselineMessages.map { (it.blocks[0] as Block.Text).text }
            assertThat(texts[0]).contains("goal: 记账")
            assertThat(texts[1]).isEqualTo("用户一")
            assertThat(texts[2]).contains("交接摘要")
            assertThat(texts[3]).contains("用户名是小明")
            assertThat(texts[4]).contains("观测文本")
        }

    @Test
    fun `no initial context omits the message`() =
        runTest {
            val llm = ScriptedLlmProvider().say("交接摘要")
            val compactor = HandoffCompactor(FakeLlmGateway(llm), TestPromptRepository())
            val in0 = input(live = listOf(render(1)), notes = listOf("笔记"))

            val result = (compactor.compact(in0) as Outcome.Ok).value

            assertThat(result.baselineMessages.first().toString()).doesNotContain("goal")
            assertThat(result.baselineMessages.last().toString()).contains("笔记")
        }

    private fun input(
        live: List<RenderItem>,
        userMessages: List<Pair<Long, Msg>> = emptyList(),
        plan: List<PlanItem> = emptyList(),
        runVars: Map<String, String> = emptyMap(),
        initialContext: String? = null,
        notes: List<String> = emptyList(),
        lastObservation: RenderItem? = null,
    ): CompactionInput =
        CompactionInput(
            threadId = ThreadId("t1"),
            system = listOf(SystemPart("系统提示")),
            live = live,
            userMessagesNewestFirst = userMessages,
            plan = plan,
            runVars = runVars,
            lastObservation = lastObservation,
            config = FakeConfigProvider().current(),
            initialContext = initialContext,
            notes = notes,
        )

    private fun render(seq: Long): RenderItem =
        RenderItem(seq, Msg(Role.USER, listOf(Block.Text("消息$seq"))), null, null)

    /** 所有角色都无模型。 */
    private object EmptyGateway : LlmGateway {
        override fun forRole(role: ModelRole): Outcome<LlmProvider> =
            Outcome.Err(AppError(code = LlmErrorCodes.NOT_CONFIGURED, message = "no model"))

        override fun forProfile(profileId: String): Outcome<LlmProvider> =
            Outcome.Err(AppError(code = LlmErrorCodes.NOT_CONFIGURED, message = "no model"))
    }

    /** 只有 MAIN 角色有模型（LIGHT 缺失，验证回退）。 */
    private class MainOnlyGateway(
        private val provider: LlmProvider,
    ) : LlmGateway {
        override fun forRole(role: ModelRole): Outcome<LlmProvider> =
            if (role == ModelRole.MAIN) {
                Outcome.Ok(provider)
            } else {
                Outcome.Err(AppError(code = LlmErrorCodes.NOT_CONFIGURED, message = "no light model"))
            }

        override fun forProfile(profileId: String): Outcome<LlmProvider> = Outcome.Ok(provider)
    }
}
