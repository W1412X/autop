/**
 * 运行笔记基线消息的公共渲染（agent-harness-overhaul WP4.2）：
 * [RunNotes.blockMessage] 与 [BaselineAssembler.notesMsg] 共用，不写两份渲染逻辑。
 */
package com.example.autop.agent.runtime.notes

import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentNotesSection
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role

/** 渲染一条笔记 USER 消息（无笔记返回 null）：超出 maxItems / maxTokens 丢最旧。 */
internal fun renderNotesBlock(
    notes: List<String>,
    cfg: AgentNotesSection,
    prompts: PromptRepository,
    estimateText: (String) -> Int,
): Msg? {
    val kept = trimmedNotes(notes, cfg, estimateText).ifEmpty { return null }
    val text =
        prompts
            .render(ID_NOTES_BLOCK, mapOf(VAR_NOTES to kept.joinToString("\n") { "- $it" }))
            .getOrNull()
            ?.text
            ?: return null
    return Msg(Role.USER, listOf(Block.Text(text)))
}

/** 超出 maxItems / maxTokens 丢最旧。 */
internal fun trimmedNotes(
    notes: List<String>,
    cfg: AgentNotesSection,
    estimateText: (String) -> Int,
): List<String> {
    var kept = notes.takeLast(cfg.maxItems)
    while (kept.isNotEmpty() && kept.sumOf(estimateText) > cfg.maxTokens) {
        kept = kept.drop(1)
    }
    return kept
}

internal const val ID_NOTES_BLOCK: String = "agent.notes.block"

private const val VAR_NOTES = "notes"
