/**
 * 运行期事件写入（docs/tech/04 §3.2）：Run 级 append 扩展、模板注入、
 * steer 写入与 RunFinished 收口；从 TurnLoop 拆出的单职责小类。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runEndedNoteId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject

/** Run 结束 liveNote 文字（附录 A.9 模板；SUCCEEDED 不写，模板缺失给 null）。 */
internal suspend fun PromptRepository.runEndedNote(status: RunOutcome): String? =
    if (status == RunOutcome.SUCCEEDED) {
        null
    } else {
        render(runEndedNoteId(status), emptyMap()).getOrNull()?.text
    }

/** Run 级事件写入扩展（threadId/runId 由句柄派生；RunController 的 pause/resume 同样经此写入）。 */
internal suspend fun RunController.append(payload: EventPayload): ThreadEvent =
    toolStore.append(threadId, runId, payload)

/** 事件写入助手。 */
internal class LoopJournal(
    private val controller: RunController,
    private val prompts: PromptRepository,
) {
    /** 渲染模板并注入为 INJECTED 消息；模板缺失时不注入。 */
    suspend fun inject(
        id: String,
        vars: Map<String, String>,
    ) {
        val text = prompts.render(id, vars).getOrNull()?.text ?: return
        injectBlocks(listOf(Block.Text(text)), UserMessageKind.INJECTED)
    }

    /** 注入一段消息。 */
    suspend fun injectBlocks(
        blocks: List<Block>,
        kind: UserMessageKind,
    ) {
        controller.append(UserMessage(blocks = blocks, kind = kind))
    }

    /** 取出排队的补充指令并逐条写为 UserMessage（docs/tech/04 §3.2；N192：steer 写 STEER、
     *  resume 恢复提示写 INJECTED，统一在工具批次间的落盘）。 */
    suspend fun drainSteers(): List<String> {
        val steers = controller.steers.drain()
        for (item in steers) {
            injectBlocks(listOf(Block.Text(item.text)), item.kind)
        }
        return steers.map { it.text }
    }

    /** 写一条工具结果（截断丢弃的调用配对用，WP5.4）。 */
    suspend fun toolResult(event: ToolResultEvent) {
        controller.append(event)
    }

    /** 写 RunFinished 并返回（收口唯一入口）；非成功结束渲染 `agent.run.ended.<outcome>` liveNote（WP8.2）。 */
    suspend fun finishEvent(
        status: RunOutcome,
        summary: String?,
        outputs: JsonObject?,
        errorCode: String?,
    ): RunFinished {
        val event =
            RunFinished(
                status = status,
                summary = summary,
                outputs = outputs,
                errorCode = errorCode,
                liveNote = prompts.runEndedNote(status),
            )
        controller.append(event)
        return event
    }
}
