package com.example.autop.agent.timeline

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.PauseReason
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunResumed
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class TimelineCoreTest {
    @Test
    fun `run meta counts only its own run and stays on its own tail`() {
        val history =
            listOf(
                event(1, 0, runStarted()),
                event(2, 100, assistant(toolCall("c1", "tap"))),
                event(3, 200, toolResult("c1", "tool.tap")),
                event(4, 300, assistant(Block.Text("第一轮"), outputTokens = 5)),
                event(5, 400, RunFinished(status = RunOutcome.SUCCEEDED, summary = null)),
                event(6, 500, user("再来一次")),
                event(7, 600, runStarted()),
                event(8, 700, assistant(Block.Text("第二轮"), outputTokens = 7)),
            )

        val entries = mergeCore(history, emptyList())

        val firstText = entries[1].row as CoreRow.Assistant
        val meta = requireNotNull(firstText.meta)
        assertThat(meta.outcome).isEqualTo(RunOutcome.SUCCEEDED)
        assertThat(meta.stepCount).isEqualTo(1)
        assertThat(meta.outputTokens).isEqualTo(5)
        assertThat(meta.durationMs).isEqualTo(400)
        // 页脚留在第一轮消息上，不挂到新一轮的尾巴。
        val lastText = entries.last().row as CoreRow.Assistant
        assertThat(lastText.text).isEqualTo("第二轮")
        assertThat(lastText.meta).isNull()
    }

    @Test
    fun `empty-output run meta becomes standalone row and keeps previous meta intact`() {
        val history =
            listOf(
                event(1, 0, runStarted()),
                event(2, 100, assistant(Block.Text("第一轮"))),
                event(3, 200, RunFinished(status = RunOutcome.SUCCEEDED, summary = null)),
                event(4, 300, runStarted()),
                event(5, 400, RunFinished(status = RunOutcome.CANCELLED, summary = null)),
            )

        val entries = mergeCore(history, emptyList())

        val firstText = entries[0].row as CoreRow.Assistant
        assertThat(firstText.meta?.outcome).isEqualTo(RunOutcome.SUCCEEDED)
        val standalone = entries.last().row as CoreRow.RunMeta
        assertThat(standalone.outcome).isEqualTo(RunOutcome.CANCELLED)
    }

    @Test
    fun `live tool call streams a running row keyed by real call id`() {
        val live = listOf(AgentEvent.ToolCallStreaming(index = 0, name = "tap", callId = "c9"))

        val row = mergeCore(emptyList(), live).single().row as CoreRow.Step

        assertThat(row.callId).isEqualTo("c9")
        assertThat(row.state).isEqualTo(StepState.RUNNING)
    }

    @Test
    fun `live tool call does not duplicate the persisted running row`() {
        val history = listOf(event(1, 0, assistant(toolCall("c9", "tap"))))
        val live = listOf(AgentEvent.ToolCallStreaming(index = 0, name = "tap", callId = "c9"))

        val entries = mergeCore(history, live)

        assertThat(entries).hasSize(1)
        assertThat((entries.single().row as CoreRow.Step).state).isEqualTo(StepState.RUNNING)
    }

    @Test
    fun `tool call moves from running to done or failed when result arrives`() {
        val history =
            listOf(
                event(1, 0, assistant(toolCall("c1", "tap"))),
                event(2, 100, toolResult("c1", "tool.tap", ok = true)),
                event(3, 200, assistant(toolCall("c2", "scroll"))),
                event(4, 300, toolResult("c2", "tool.scroll", ok = false, errorCode = "E_FAIL")),
            )

        val rows = mergeCore(history, emptyList()).map { it.row }

        assertThat(rows).hasSize(2)
        val done = rows[0] as CoreRow.Step
        assertThat(done.state).isEqualTo(StepState.DONE)
        assertThat(done.argsJson).isEqualTo("{}")
        val failed = rows[1] as CoreRow.Step
        assertThat(failed.state).isEqualTo(StepState.FAILED)
        assertThat(failed.errorCode).isEqualTo("E_FAIL")
    }

    @Test
    fun `finished summary duplicating assistant text is dropped but meta remains`() {
        val history =
            listOf(
                event(1, 0, runStarted()),
                event(2, 100, assistant(Block.Text("搞定"))),
                event(3, 200, RunFinished(status = RunOutcome.SUCCEEDED, summary = "搞定")),
            )

        val text = mergeCore(history, emptyList()).single().row as CoreRow.Assistant

        assertThat(text.meta).isNotNull()
        assertThat(text.meta?.summary).isNull()
    }

    @Test
    fun `run meta becomes standalone row when run ends with tool step`() {
        val history =
            listOf(
                event(1, 0, runStarted()),
                event(2, 100, assistant(toolCall("c1", "tap"))),
                event(3, 200, toolResult("c1", "tool.tap")),
                event(4, 300, RunFinished(status = RunOutcome.SUCCEEDED, summary = null)),
            )

        val entries = mergeCore(history, emptyList())

        val meta = entries.last().row as CoreRow.RunMeta
        assertThat(meta.stepCount).isEqualTo(1)
        assertThat(meta.durationMs).isEqualTo(300)
    }

    @Test
    fun `live text deltas merge into one streaming assistant row`() {
        val entries =
            mergeCore(
                emptyList(),
                listOf(AgentEvent.TextDelta("你"), AgentEvent.TextDelta("好")),
            )

        val row = entries.single().row as CoreRow.Assistant
        assertThat(row.text).isEqualTo("你好")
        assertThat(row.streaming).isTrue()
    }

    @Test
    fun `plan update replaces previous plan row`() {
        val history =
            listOf(
                event(1, 0, user("做个计划")),
                event(2, 100, PlanUpdated(items = listOf(PlanItem("a", PlanStatus.PENDING)))),
                event(3, 200, PlanUpdated(items = listOf(PlanItem("b", PlanStatus.DONE)))),
            )

        val rows = mergeCore(history, emptyList()).map { it.row }

        assertThat(rows)
            .containsExactly(
                CoreRow.User("做个计划", isSteer = false),
                CoreRow.Plan(listOf(PlanItem("b", PlanStatus.DONE))),
            ).inOrder()
    }

    @Test
    fun `pause banners are replaced on resume`() {
        val history =
            listOf(
                event(1, 0, RunPaused(reason = PauseReason.MANUAL, detail = "停一下")),
                event(2, 100, RunResumed(userNote = "继续")),
            )

        assertThat(mergeCore(history, emptyList())).isEmpty()
    }

    @Test
    fun `steer user message is tagged and injected is hidden`() {
        val history =
            listOf(
                event(
                    1,
                    0,
                    UserMessage(blocks = listOf(Block.Text("电量 80%")), kind = UserMessageKind.INJECTED),
                ),
                event(2, 100, UserMessage(blocks = listOf(Block.Text("顺便清缓存")), kind = UserMessageKind.STEER)),
            )

        val rows = mergeCore(history, emptyList()).map { it.row }

        assertThat(rows).containsExactly(CoreRow.User("顺便清缓存", isSteer = true))
    }

    private fun event(
        seq: Long,
        createdAt: Long,
        payload: EventPayload,
    ): ThreadEvent =
        ThreadEvent(
            threadId = ThreadId("thread-1"),
            seq = seq,
            runId = null,
            createdAt = createdAt,
            payload = payload,
        )

    private fun runStarted(): RunStarted =
        RunStarted(
            runId = RunId("r1"),
            configDiff = buildJsonObject { },
            promptVersions = emptyMap(),
            modelProfileId = "m",
            toolIds = emptyList(),
        )

    private fun user(text: String): UserMessage =
        UserMessage(blocks = listOf(Block.Text(text)), kind = UserMessageKind.INPUT)

    private fun assistant(
        vararg blocks: Block,
        outputTokens: Int = 0,
    ): AssistantMessage =
        AssistantMessage(
            blocks = blocks.toList(),
            usage = Usage(inputTokens = 1, outputTokens = outputTokens),
            stopReason = StopReason.END_TURN,
            modelProfileId = "m",
        )

    private fun toolCall(
        id: String,
        name: String,
    ): Block.ToolCall = Block.ToolCall(id = id, name = name, argsJson = "{}")

    private fun toolResult(
        callId: String,
        toolId: String,
        ok: Boolean = true,
        errorCode: String? = null,
    ): ToolResultEvent =
        ToolResultEvent(
            callId = callId,
            toolId = toolId,
            ok = ok,
            blocks = listOf(Block.Text(if (ok) "ok" else "失败原因")),
            errorCode = errorCode,
            durationMs = 80L,
        )
}
