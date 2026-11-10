/**
 * 运行笔记（agent-harness-overhaul WP3.7）：当前笔记 = 最近 Baseline.notes + 其后的 RunNoteAdded；
 * 只在压缩与 fork 基线里以一条 USER 消息出现；运行中模型通过自己的 note 调用记录可见。
 */
package com.example.autop.agent.runtime.notes

import com.example.autop.agent.Baseline
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentNotesSection
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/** 笔记汇总与渲染。 */
internal class RunNotes(
    private val store: ThreadStore,
    private val prompts: PromptRepository,
    private val estimateText: (String) -> Int,
) {
    /** 当前全部笔记（时间顺序）。 */
    suspend fun current(threadId: ThreadId): List<String> = notesOf(store.eventsSinceLatestBaseline(threadId))

    /** 从事件列表汇总笔记（fork 取 atSeq 时刻的历史时用）：最近 Baseline 的 notes + 其后的 RunNoteAdded。 */
    fun notesOf(events: List<ThreadEvent>): List<String> {
        val baselineIdx = events.indexOfLast { it.payload is Baseline }
        val baselineNotes = (events.getOrNull(baselineIdx)?.payload as? Baseline)?.notes.orEmpty()
        val added = events.drop(baselineIdx + 1).mapNotNull { (it.payload as? RunNoteAdded)?.text }
        return baselineNotes + added
    }

    /** 基线里的笔记消息（无笔记返回 null）：只在压缩与 fork 基线里以一条 USER 消息出现（WP3.7）。 */
    fun blockMessage(
        notes: List<String>,
        cfg: AgentNotesSection,
    ): Msg? {
        val kept = trimmed(notes, cfg).ifEmpty { return null }
        val text =
            prompts
                .render(ID, mapOf(VAR_NOTES to kept.joinToString("\n") { "- $it" }))
                .getOrNull()
                ?.text ?: return null
        return Msg(Role.USER, listOf(Block.Text(text)))
    }

    /** 压缩与 fork 写入新 Baseline 的笔记（原文照搬）。 */
    suspend fun snapshot(threadId: ThreadId): List<String> = current(threadId)

    /** 超出 maxItems / maxTokens 丢最旧。 */
    internal fun trimmed(
        notes: List<String>,
        cfg: AgentNotesSection,
    ): List<String> {
        var kept = notes.takeLast(cfg.maxItems)
        while (kept.isNotEmpty() && kept.sumOf(estimateText) > cfg.maxTokens) {
            kept = kept.drop(1)
        }
        return kept
    }

    private companion object {
        const val ID = "agent.notes.block"
        const val VAR_NOTES = "notes"
    }
}
