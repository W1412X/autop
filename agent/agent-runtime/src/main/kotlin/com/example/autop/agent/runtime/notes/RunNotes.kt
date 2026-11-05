/**
 * 运行笔记（docs/tech/04 §3.13，R1/B3）：当前笔记 = 最近 Baseline.notes + 其后的 RunNoteAdded。
 * 渲染为 system 最后一个 SystemPart，每轮重新生成、不打缓存标记；只影响渲染，事件日志保留。
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
import com.example.autop.llm.SystemPart

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

    /** 笔记块（无笔记返回 null）；超出 maxItems / maxTokens 丢最旧。 */
    suspend fun systemPart(
        threadId: ThreadId,
        cfg: AgentNotesSection,
    ): SystemPart? {
        val kept = trim(current(threadId), cfg) ?: return null
        val text =
            prompts
                .render(ID, mapOf("notes" to kept.joinToString("\n") { "- $it" }))
                .getOrNull()
                ?.text
        return text?.let { SystemPart(text = it, cacheBreakpoint = false) }
    }

    /** 压缩与 fork 写入新 Baseline 的笔记（原文照搬）。 */
    suspend fun snapshot(threadId: ThreadId): List<String> = current(threadId)

    /** 超出上限丢最旧；全部丢完返回 null。 */
    private fun trim(
        notes: List<String>,
        cfg: AgentNotesSection,
    ): List<String>? {
        var kept = notes.takeLast(cfg.maxItems)
        while (kept.isNotEmpty() && kept.sumOf(estimateText) > cfg.maxTokens) {
            kept = kept.drop(1)
        }
        return kept.ifEmpty { null }
    }

    private companion object {
        const val ID = "agent.notes.block"
    }
}
