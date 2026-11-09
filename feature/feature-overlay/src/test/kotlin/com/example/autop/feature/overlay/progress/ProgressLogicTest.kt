package com.example.autop.feature.overlay.progress

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.PauseReason
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunPaused
import com.example.autop.agent.RunStarted
import com.example.autop.agent.RunState
import com.example.autop.agent.ThreadCreated
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.TOOL_MARK_NOTE
import com.example.autop.agent.timeline.StepState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class ProgressLogicTest {
    @Test
    fun `user messages map to user rows and steer is tagged`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    UserMessage(blocks = listOf(Block.Text("打开设置")), kind = UserMessageKind.INPUT),
                    UserMessage(blocks = listOf(Block.Text("顺便清缓存")), kind = UserMessageKind.STEER),
                ),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.User("打开设置", isSteer = false),
                ProgressRow.User("顺便清缓存", isSteer = true),
            ).inOrder()
    }

    @Test
    fun `assistant message emits thinking then text rows`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    assistant(blocks = listOf(Block.Thinking("想一下"), Block.Text("好的"))),
                    assistant(blocks = listOf(Block.Thinking("再想"))),
                ),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.Thinking("想一下"),
                ProgressRow.Assistant("好的"),
                ProgressRow.Thinking("再想"),
            ).inOrder()
    }

    @Test
    fun `tool result carries duration and failure text`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    toolResult(toolId = "tool.tap", ok = true, blocks = emptyList()),
                    toolResult(toolId = "tool.scroll", ok = false, blocks = listOf(Block.Text("节点不可点击"))),
                    toolResult(toolId = "tool.observe", ok = false, blocks = emptyList(), errorCode = "E_TIMEOUT"),
                ),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.Tool("tool.tap", state = StepState.DONE, durationMs = 80, errorText = null),
                ProgressRow.Tool("tool.scroll", state = StepState.FAILED, durationMs = 80, errorText = "节点不可点击"),
                ProgressRow.Tool("tool.observe", state = StepState.FAILED, durationMs = 80, errorText = "E_TIMEOUT"),
            ).inOrder()
    }

    @Test
    fun `plan update replaces previous plan row`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    UserMessage(blocks = listOf(Block.Text("做个计划")), kind = UserMessageKind.INPUT),
                    PlanUpdated(items = listOf(PlanItem("a", PlanStatus.PENDING))),
                    PlanUpdated(items = listOf(PlanItem("b", PlanStatus.DONE))),
                ),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.User("做个计划", isSteer = false),
                ProgressRow.Plan(listOf(PlanItem("b", PlanStatus.DONE))),
            ).inOrder()
    }

    @Test
    fun `run finished maps to standalone finished row when no trailing assistant`() {
        val rows = progressRowsOf(eventsOf(RunFinished(status = RunOutcome.SUCCEEDED, summary = "搞定")))
        assertThat(rows)
            .containsExactly(
                ProgressRow.Finished(
                    status = RunOutcome.SUCCEEDED,
                    summary = "搞定",
                    stepCount = 0,
                    durationMs = 0,
                    outputTokens = 0,
                ),
            )
    }

    @Test
    fun `finished meta attaches to trailing assistant row with stats`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    assistant(blocks = listOf(Block.Text("好的")), outputTokens = 7),
                    RunFinished(status = RunOutcome.SUCCEEDED, summary = "任务摘要"),
                ),
            )
        val text = rows.single() as ProgressRow.Assistant
        assertThat(text.meta?.status).isEqualTo(RunOutcome.SUCCEEDED)
        assertThat(text.meta?.summary).isEqualTo("任务摘要")
        assertThat(text.meta?.outputTokens).isEqualTo(7)
    }

    @Test
    fun `finished summary duplicating assistant text is dropped but meta remains`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    assistant(blocks = listOf(Block.Text("搞定"))),
                    RunFinished(status = RunOutcome.SUCCEEDED, summary = "搞定"),
                ),
            )
        val text = rows.single() as ProgressRow.Assistant
        assertThat(text.meta).isNotNull()
        assertThat(text.meta?.summary).isNull()
    }

    @Test
    fun `unhandled events are skipped`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    ThreadCreated(title = "t"),
                    RunPaused(reason = PauseReason.USER_TOUCH),
                    RunNoteAdded(text = "note"),
                ),
            )
        assertThat(rows).isEmpty()
    }

    @Test
    fun `injected context message is hidden from rows`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    UserMessage(
                        blocks = listOf(Block.Text("当前环境：电量 80%")),
                        kind = UserMessageKind.INJECTED,
                    ),
                    UserMessage(blocks = listOf(Block.Text("打开设置")), kind = UserMessageKind.INPUT),
                ),
            )
        assertThat(rows).containsExactly(ProgressRow.User("打开设置", isSteer = false))
    }

    @Test
    fun `assistant blocks keep original order`() {
        val rows =
            progressRowsOf(
                eventsOf(assistant(blocks = listOf(Block.Text("先"), Block.Thinking("想"), Block.Text("后")))),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.Assistant("先"),
                ProgressRow.Thinking("想"),
                ProgressRow.Assistant("后"),
            ).inOrder()
    }

    @Test
    fun `failed tool error text takes first text block only`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    toolResult(
                        toolId = "tool.scroll",
                        ok = false,
                        blocks = listOf(Block.Text("节点不可点击"), Block.Text("其余细节不直接展示")),
                    ),
                ),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.Tool("tool.scroll", state = StepState.FAILED, durationMs = 80, errorText = "节点不可点击"),
            )
    }

    @Test
    fun `live text deltas merge into one streaming assistant row`() {
        val rows =
            progressRowsOf(
                events = emptyList(),
                live = listOf(AgentEvent.TextDelta("你"), AgentEvent.TextDelta("好")),
            )
        assertThat(rows).containsExactly(ProgressRow.Assistant("你好", streaming = true))
    }

    @Test
    fun `live thinking delta becomes streaming thinking row after history rows`() {
        val rows =
            progressRowsOf(
                events = eventsOf(UserMessage(blocks = listOf(Block.Text("hi")), kind = UserMessageKind.INPUT)),
                live = listOf(AgentEvent.ThinkingDelta("思考中")),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.User("hi", isSteer = false),
                ProgressRow.Thinking("思考中", streaming = true),
            ).inOrder()
    }

    @Test
    fun `finished summary matching one text block is dropped`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    assistant(blocks = listOf(Block.Text("做了A"), Block.Text("做了B"))),
                    RunFinished(status = RunOutcome.SUCCEEDED, summary = "做了B"),
                ),
            )
        val text = rows.last() as ProgressRow.Assistant
        assertThat(text.meta?.summary).isNull()
    }

    @Test
    fun `header shows waiting hint while waiting for answer`() {
        val spec =
            progressHeaderSpec(
                activeRun(status = RunStatus.WAITING_USER, waitingQuestion = "打开哪个应用"),
                finished = null,
            )
        assertThat(spec.tone).isEqualTo(StatusTone.WAITING)
        assertThat(spec.chipText).isEqualTo(R.string.panel_status_waiting)
        assertThat(spec.hint).isEqualTo(R.string.progress_hint_waiting)
        assertThat(spec.runControls).isTrue()
    }

    @Test
    fun `header shows paused hint and blocked hint from pause reason`() {
        val paused = progressHeaderSpec(activeRun(status = RunStatus.PAUSED), finished = null)
        assertThat(paused.tone).isEqualTo(StatusTone.PAUSED)
        assertThat(paused.hint).isEqualTo(R.string.progress_hint_paused)
        val blocked = progressHeaderSpec(activeRun(pauseReason = PauseReason.POLICY_BLOCKED), finished = null)
        assertThat(blocked.tone).isEqualTo(StatusTone.BLOCKED)
        assertThat(blocked.chipText).isEqualTo(R.string.panel_status_blocked)
        assertThat(blocked.hint).isEqualTo(R.string.progress_hint_blocked)
    }

    @Test
    fun `header keeps run controls and has no hint while running`() {
        val spec = progressHeaderSpec(activeRun(), finished = null)
        assertThat(spec.tone).isEqualTo(StatusTone.RUNNING)
        assertThat(spec.hint).isNull()
        assertThat(spec.runControls).isTrue()
    }

    @Test
    fun `header collapses run controls and shows outcome after finish`() {
        val spec =
            progressHeaderSpec(
                active = null,
                finished =
                    ProgressRow.Finished(
                        RunOutcome.FAILED,
                        null,
                        stepCount = 3,
                        durationMs = 1_000,
                        outputTokens = 5,
                    ),
            )
        assertThat(spec.tone).isEqualTo(StatusTone.BLOCKED)
        assertThat(spec.chipText).isEqualTo(R.string.progress_outcome_failed)
        assertThat(spec.runControls).isFalse()
    }

    @Test
    fun `header is queued neutral when nothing active or finished`() {
        val spec = progressHeaderSpec(active = null, finished = null)
        assertThat(spec.tone).isEqualTo(StatusTone.NEUTRAL)
        assertThat(spec.chipText).isEqualTo(R.string.panel_status_queued)
        assertThat(spec.runControls).isFalse()
    }

    @Test
    fun `回退协议与工具标记文本不冒充助手正文`() {
        val xmlMark = "好的\n<tool_call><function=tap></function></tool_call>"
        val rows =
            progressRowsOf(
                eventsOf(
                    assistant(blocks = listOf(Block.Text("""{"tool_calls":[{"name":"tap","arguments":{}}]}"""))),
                    assistant(blocks = listOf(Block.Text("""{"final":"已完成"}"""))),
                    assistant(blocks = listOf(Block.Text(xmlMark))),
                ),
            )
        assertThat(rows)
            .containsExactly(
                ProgressRow.Assistant("已完成"),
                ProgressRow.Assistant("$TOOL_MARK_NOTE\n\n$xmlMark"),
            ).inOrder()
    }

    @Test
    fun `finished meta stays on own run tail and stats do not accumulate across runs`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    UserMessage(blocks = listOf(Block.Text("第一问")), kind = UserMessageKind.INPUT),
                    runStarted(),
                    toolResult(toolId = "tool.tap", ok = true, blocks = emptyList()),
                    assistant(blocks = listOf(Block.Text("第一轮")), outputTokens = 5),
                    RunFinished(status = RunOutcome.SUCCEEDED, summary = null),
                    UserMessage(blocks = listOf(Block.Text("第二问")), kind = UserMessageKind.INPUT),
                    runStarted(),
                    assistant(blocks = listOf(Block.Text("第二轮")), outputTokens = 7),
                ),
            )

        val firstText = rows[2] as ProgressRow.Assistant
        val meta = requireNotNull(firstText.meta)
        assertThat(meta.status).isEqualTo(RunOutcome.SUCCEEDED)
        assertThat(meta.stepCount).isEqualTo(1)
        assertThat(meta.outputTokens).isEqualTo(5)
        // 结束元信息留在第一轮消息上，不挂到新一轮的尾巴。
        val lastText = rows.last() as ProgressRow.Assistant
        assertThat(lastText.text).isEqualTo("第二轮")
        assertThat(lastText.meta).isNull()
    }

    @Test
    fun `empty-output run meta becomes standalone row and keeps previous meta intact`() {
        val rows =
            progressRowsOf(
                eventsOf(
                    runStarted(),
                    assistant(blocks = listOf(Block.Text("第一轮"))),
                    RunFinished(status = RunOutcome.SUCCEEDED, summary = null),
                    runStarted(),
                    RunFinished(status = RunOutcome.CANCELLED, summary = null),
                ),
            )

        val firstText = rows[0] as ProgressRow.Assistant
        assertThat(firstText.meta?.status).isEqualTo(RunOutcome.SUCCEEDED)
        val standalone = rows.last() as ProgressRow.Finished
        assertThat(standalone.status).isEqualTo(RunOutcome.CANCELLED)
    }

    private fun activeRun(
        status: RunStatus = RunStatus.RUNNING,
        pauseReason: PauseReason? = null,
        waitingQuestion: String? = null,
    ): ActiveRunInfo {
        val record =
            RunRecord(
                id = RunId("run-1"),
                taskId = null,
                threadId = ThreadId("thread-1"),
                origin = RunOrigin.CHAT,
                status = status,
                triggerContext = emptyMap(),
                createdAt = 0L,
                startedAt = null,
                endedAt = null,
                summary = null,
                errorCode = null,
                pauseReason = pauseReason,
                waitingQuestion = waitingQuestion,
            )
        return ActiveRunInfo(record, RunState.SAMPLING, "正在执行", 3, emptyList())
    }

    private fun eventsOf(vararg payloads: EventPayload): List<ThreadEvent> =
        payloads.mapIndexed { index, payload ->
            ThreadEvent(
                threadId = ThreadId("thread-1"),
                seq = index.toLong(),
                runId = null,
                createdAt = 0L,
                payload = payload,
            )
        }

    private fun runStarted(): RunStarted =
        RunStarted(
            runId = RunId("run-1"),
            configDiff = buildJsonObject { },
            promptVersions = emptyMap(),
            modelProfileId = "m",
            toolIds = emptyList(),
        )

    private fun assistant(
        blocks: List<Block>,
        outputTokens: Int = 0,
    ): AssistantMessage =
        AssistantMessage(
            blocks = blocks,
            usage = Usage(inputTokens = 1, outputTokens = outputTokens),
            stopReason = StopReason.END_TURN,
            modelProfileId = "m",
        )

    private fun toolResult(
        toolId: String,
        ok: Boolean,
        blocks: List<Block>,
        errorCode: String? = null,
    ): ToolResultEvent =
        ToolResultEvent(
            callId = "call-$toolId",
            toolId = toolId,
            ok = ok,
            blocks = blocks,
            errorCode = errorCode,
            durationMs = 80L,
        )
}
