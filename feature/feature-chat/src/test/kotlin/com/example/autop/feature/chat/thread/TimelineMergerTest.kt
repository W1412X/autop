/**
 * [mergeTimeline] 单测（docs/tech/08 §7：时间线归并必须测流式文本合并、
 * 工具调用开始→完成的状态切换、StepGroup 折叠规则、fork 点判定）。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.AgentEvent
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunStarted
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.protocol.TOOL_MARK_NOTE
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import com.example.autop.llm.Usage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class TimelineMergerTest {
    @Test
    fun `streaming deltas merge into one entry and thinking splits`() {
        val live =
            listOf(
                AgentEvent.TextDelta("你好"),
                AgentEvent.TextDelta("，世界"),
                AgentEvent.ThinkingDelta("嗯"),
                AgentEvent.ThinkingDelta("…"),
            )
        val entries =
            mergeTimeline(history = emptyList(), live = live, stepGroupThreshold = GROUP_THRESHOLD)
        assertThat(entries).hasSize(2)
        val text = entries[0].item as TimelineItem.AssistantText
        assertThat(text.text).isEqualTo("你好，世界")
        assertThat(text.streaming).isTrue()
        val thinking = entries[1].item as TimelineItem.ThinkingBlock
        assertThat(thinking.text).isEqualTo("嗯…")
        assertThat(thinking.streaming).isTrue()
    }

    @Test
    fun `tool call moves from running to done or failed when result arrives`() {
        val call = assistant(toolCall("c1", "tap"))
        val running = mergeTimeline(listOf(event(1, call)), emptyList(), GROUP_THRESHOLD)
        assertThat((running.single().item as TimelineItem.StepRow).state).isEqualTo(StepState.RUNNING)
        val done =
            mergeTimeline(
                listOf(event(1, call), event(2, toolResult("c1", "tap", ok = true))),
                emptyList(),
                GROUP_THRESHOLD,
            )
        assertThat((done.single().item as TimelineItem.StepRow).state).isEqualTo(StepState.DONE)
        val failed =
            mergeTimeline(
                listOf(event(1, call), event(2, toolResult("c1", "tap", ok = false))),
                emptyList(),
                GROUP_THRESHOLD,
            )
        assertThat((failed.single().item as TimelineItem.StepRow).state).isEqualTo(StepState.FAILED)
    }

    @Test
    fun `consecutive successful read steps fold at threshold`() {
        val folded = mergeTimeline(readSteps(3), emptyList(), stepGroupThreshold = GROUP_THRESHOLD)
        val group = folded.single().item as TimelineItem.StepGroup
        assertThat(group.count).isEqualTo(3)
        assertThat(group.steps.map { it.toolId }).containsExactly("observe", "observe", "observe")
        val unfolded = mergeTimeline(readSteps(2), emptyList(), stepGroupThreshold = GROUP_THRESHOLD)
        assertThat(unfolded.map { it.item }).hasSize(2)
    }

    @Test
    fun `steps get sequential chain numbers including folded group members`() {
        val history =
            listOf(
                event(1, assistant(toolCall("c1", "observe"))),
                event(2, toolResult("c1", "observe")),
                event(3, assistant(toolCall("c2", "observe"))),
                event(4, toolResult("c2", "observe")),
                event(5, assistant(toolCall("c3", "tap"))),
                event(6, toolResult("c3", "tap")),
            )
        val entries = mergeTimeline(history, emptyList(), stepGroupThreshold = 2)
        val group = entries[0].item as TimelineItem.StepGroup
        assertThat(group.steps.map { it.stepNo }).containsExactly(1, 2).inOrder()
        assertThat((entries[1].item as TimelineItem.StepRow).stepNo).isEqualTo(3)
    }

    @Test
    fun `fork points appear where assistant batch ends without unpaired tool call`() {
        val message = assistant(Block.Text("好的"))
        val withMessage = mergeTimeline(listOf(event(1, message)), emptyList(), GROUP_THRESHOLD)
        assertThat(withMessage.single().forkable).isTrue()
        val call = assistant(toolCall("c1", "tap"))
        val unpaired = mergeTimeline(listOf(event(1, call)), emptyList(), GROUP_THRESHOLD)
        assertThat(unpaired.single().forkable).isFalse()
        val paired =
            mergeTimeline(
                listOf(event(1, call), event(2, toolResult("c1", "tap"))),
                emptyList(),
                GROUP_THRESHOLD,
            )
        assertThat(paired.last().forkable).isTrue()
    }

    @Test
    fun `run finished attaches meta row with stats to trailing assistant text`() {
        val history =
            listOf(
                event(1, runStarted()),
                event(2, assistant(Block.Text("完成"), outputTokens = 7)),
                event(3, RunFinished(status = RunOutcome.SUCCEEDED, summary = "已发送")),
            )
        val entries = mergeTimeline(history, emptyList(), GROUP_THRESHOLD)
        val text = entries.last().item as TimelineItem.AssistantText
        val meta = requireNotNull(text.meta)
        assertThat(meta.outcome).isEqualTo(RunOutcome.SUCCEEDED)
        assertThat(meta.summary).isEqualTo("已发送")
        assertThat(meta.outputTokens).isEqualTo(7)
        assertThat(meta.durationMs).isEqualTo(2000)
    }

    @Test
    fun `run meta becomes standalone entry when thread ends with tool step`() {
        val history =
            listOf(
                event(1, runStarted()),
                event(2, assistant(toolCall("c1", "tap"))),
                event(3, toolResult("c1", "tap")),
                event(4, RunFinished(status = RunOutcome.SUCCEEDED, summary = null)),
            )
        val entries = mergeTimeline(history, emptyList(), GROUP_THRESHOLD)
        val meta = entries.last().item as TimelineItem.RunMeta
        assertThat(meta.stepCount).isEqualTo(1)
    }

    @Test
    fun `injected system context is not shown as user bubble`() {
        val history =
            listOf(
                event(1, user("当前环境：电量 80%", UserMessageKind.INJECTED)),
                event(2, user("发个短信", UserMessageKind.INPUT)),
            )
        val entries = mergeTimeline(history, emptyList(), GROUP_THRESHOLD)

        val bubble = entries.single().item as TimelineItem.UserBubble
        assertThat(bubble.text).isEqualTo("发个短信")
    }

    @Test
    fun `user bubble carries attached images`() {
        val image = Block.Image(fileRef = "threads/t/img/pick-1-0.jpg", mime = "image/jpeg", width = 4, height = 4)
        val history =
            listOf(event(1, UserMessage(blocks = listOf(Block.Text("这是什么"), image), kind = UserMessageKind.INPUT)))

        val bubble = mergeTimeline(history, emptyList(), GROUP_THRESHOLD).single().item as TimelineItem.UserBubble

        assertThat(bubble.text).isEqualTo("这是什么")
        assertThat(bubble.images).containsExactly("threads/t/img/pick-1-0.jpg")
    }

    @Test
    fun `registry tool ids normalize so read steps still fold`() {
        val history =
            (1..3).flatMap { index ->
                listOf(
                    event((index * 2 - 1).toLong(), assistant(toolCall("r$index", "observe"))),
                    event((index * 2).toLong(), toolResult("r$index", "tool.observe")),
                )
            }

        val entries = mergeTimeline(history, emptyList(), stepGroupThreshold = 2)

        val group = entries.single().item as TimelineItem.StepGroup
        assertThat(group.steps.map { it.toolId }).containsExactly("observe", "observe", "observe")
        assertThat(stepLabelOf(group.steps.first().toolId)).isEqualTo(StepLabel.OBSERVE)
    }

    @Test
    fun `result card summary duplicating final assistant text is dropped`() {
        val duplicated =
            mergeTimeline(
                listOf(
                    event(1, assistant(Block.Text("已完成发送"))),
                    event(2, RunFinished(status = RunOutcome.SUCCEEDED, summary = "已完成发送")),
                ),
                emptyList(),
                GROUP_THRESHOLD,
            )
        val distinct =
            mergeTimeline(
                listOf(
                    event(1, assistant(Block.Text("正文内容"))),
                    event(2, RunFinished(status = RunOutcome.SUCCEEDED, summary = "任务摘要")),
                ),
                emptyList(),
                GROUP_THRESHOLD,
            )

        assertThat((duplicated.last().item as TimelineItem.AssistantText).meta?.summary).isNull()
        assertThat((distinct.last().item as TimelineItem.AssistantText).meta?.summary).isEqualTo("任务摘要")
    }

    @Test
    fun `回退协议与工具标记文本不冒充助手正文`() {
        val xmlMark = "好的\n<tool_call><function=tap></function></tool_call>"
        val history =
            listOf(
                event(1, assistant(Block.Text("""{"tool_calls":[{"name":"tap","arguments":{}}]}"""))),
                event(2, assistant(Block.Text("""{"final":"已完成"}"""))),
                event(3, assistant(Block.Text(xmlMark))),
            )
        val entries = mergeTimeline(history, emptyList(), GROUP_THRESHOLD)
        assertThat(entries).hasSize(2)
        assertThat((entries[0].item as TimelineItem.AssistantText).text).isEqualTo("已完成")
        assertThat((entries[1].item as TimelineItem.AssistantText).text)
            .isEqualTo("$TOOL_MARK_NOTE\n\n$xmlMark")
    }

    @Test
    fun `tool result links become step jump chips`() {
        val data =
            buildJsonObject {
                put(
                    "links",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("kind", JsonPrimitive("task_detail"))
                                put("taskId", JsonPrimitive("t1"))
                                put("label", JsonPrimitive("早报任务"))
                            },
                        )
                        add(
                            buildJsonObject {
                                put("kind", JsonPrimitive("task_edit"))
                                put("taskId", JsonPrimitive("t1"))
                            },
                        )
                        add(
                            buildJsonObject {
                                put("kind", JsonPrimitive("task_detail"))
                                put("taskId", JsonPrimitive("t1"))
                            },
                        )
                    },
                )
            }
        val history =
            listOf(
                event(1, assistant(toolCall("c1", "list_tasks"))),
                event(2, toolResult("c1", "list_tasks", data = data)),
            )

        val row = mergeTimeline(history, emptyList(), GROUP_THRESHOLD).single().item as TimelineItem.StepRow

        assertThat(row.links.map { it.kind }).containsExactly("task_detail", "task_edit").inOrder()
        assertThat(row.links.first().label).isEqualTo("早报任务")
        assertThat(row.links.first().taskId).isEqualTo("t1")
    }

    @Test
    fun `malformed tool data yields no links`() {
        val history =
            listOf(
                event(1, assistant(toolCall("c1", "list_tasks"))),
                event(
                    2,
                    toolResult("c1", "list_tasks", data = buildJsonObject { put("links", JsonPrimitive("oops")) }),
                ),
            )

        val row = mergeTimeline(history, emptyList(), GROUP_THRESHOLD).single().item as TimelineItem.StepRow

        assertThat(row.links).isEmpty()
    }

    @Test
    fun `run meta counts only its own run and stays on its own tail`() {
        val history =
            listOf(
                event(1, runStarted()),
                event(2, assistant(toolCall("c1", "tap"))),
                event(3, toolResult("c1", "tap")),
                event(4, assistant(Block.Text("第一轮"), outputTokens = 5)),
                event(5, RunFinished(status = RunOutcome.SUCCEEDED, summary = null)),
                event(6, user("再来一次", UserMessageKind.INPUT)),
                event(7, runStarted()),
                event(8, assistant(Block.Text("第二轮"), outputTokens = 7)),
            )

        val entries = mergeTimeline(history, emptyList(), GROUP_THRESHOLD)

        val firstText = entries[1].item as TimelineItem.AssistantText
        val meta = requireNotNull(firstText.meta)
        assertThat(meta.outcome).isEqualTo(RunOutcome.SUCCEEDED)
        assertThat(meta.stepCount).isEqualTo(1)
        assertThat(meta.outputTokens).isEqualTo(5)
        assertThat(meta.durationMs).isEqualTo(4000)
        // 页脚留在第一轮消息上，不挂到新一轮的尾巴。
        val lastText = entries.last().item as TimelineItem.AssistantText
        assertThat(lastText.text).isEqualTo("第二轮")
        assertThat(lastText.meta).isNull()
    }

    @Test
    fun `live tool call streams a running row keyed by real call id`() {
        val live = listOf(AgentEvent.ToolCallStreaming(index = 0, name = "tap", callId = "c9"))

        val row = mergeTimeline(emptyList(), live, GROUP_THRESHOLD).single().item as TimelineItem.StepRow

        assertThat(row.callId).isEqualTo("c9")
        assertThat(row.state).isEqualTo(StepState.RUNNING)
    }

    @Test
    fun `live tool call does not duplicate the persisted running row`() {
        val history = listOf(event(1, assistant(toolCall("c9", "tap"))))
        val live = listOf(AgentEvent.ToolCallStreaming(index = 0, name = "tap", callId = "c9"))

        val entries = mergeTimeline(history, live, GROUP_THRESHOLD)

        assertThat(entries).hasSize(1)
        assertThat((entries.single().item as TimelineItem.StepRow).state).isEqualTo(StepState.RUNNING)
    }

    private companion object {
        const val GROUP_THRESHOLD = 3
    }
}

private val THREAD = ThreadId("t1")

private fun event(
    seq: Long,
    payload: EventPayload,
): ThreadEvent = ThreadEvent(threadId = THREAD, seq = seq, runId = null, createdAt = seq * 1000, payload = payload)

private fun assistant(
    vararg blocks: Block,
    outputTokens: Int = 0,
): EventPayload =
    AssistantMessage(
        blocks = blocks.toList(),
        usage = Usage(inputTokens = 1, outputTokens = outputTokens),
        stopReason = StopReason.END_TURN,
        modelProfileId = "m",
    )

private fun user(
    text: String,
    kind: UserMessageKind,
): EventPayload = UserMessage(blocks = listOf(Block.Text(text)), kind = kind)

private fun toolCall(
    id: String,
    tool: String,
): Block.ToolCall = Block.ToolCall(id = id, name = tool, argsJson = "{}")

private fun toolResult(
    callId: String,
    tool: String,
    ok: Boolean = true,
    data: JsonElement? = null,
): EventPayload =
    ToolResultEvent(
        callId = callId,
        toolId = tool,
        ok = ok,
        blocks = listOf(Block.Text("ok")),
        data = data,
        durationMs = 100,
    )

/** count 对 observe 调用 + 结果（全部成功读类步骤，用于折叠规则）。 */
private fun readSteps(count: Int): List<ThreadEvent> =
    (1..count).flatMap { index ->
        val callId = "r$index"
        listOf(
            event((index * 2 - 1).toLong(), assistant(toolCall(callId, "observe"))),
            event((index * 2).toLong(), toolResult(callId, "observe")),
        )
    }

private fun runStarted(): EventPayload =
    RunStarted(
        runId = RunId("r1"),
        configDiff = buildJsonObject { },
        promptVersions = emptyMap(),
        modelProfileId = "m",
        toolIds = emptyList(),
    )
