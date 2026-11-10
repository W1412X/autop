/**
 * 长文本截断规则 `history.long_text_truncate`（docs/tech/04 §3.4）：
 * 单个 Text 块超过 `maxChars` 时保留开头 `headChars` 与结尾 `tailChars`，
 * 中间替换为模板 `agent.history.truncated`。
 */
package com.example.autop.agent.runtime.rules

import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.RenderItem
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block

/** 长文本截断。 */
internal class LongTextTruncateRule(
    private val prompts: PromptRepository,
) : HistoryRenderRule {
    override val id: String = ID

    override fun apply(
        items: List<RenderItem>,
        config: ConfigSnapshot,
    ): List<RenderItem> {
        val cfg = config[AgentConfig.HISTORY].truncate
        // N209：maxChars ≤ 0 无限制。
        if (cfg.maxChars <= 0) return items
        return items.map { item ->
            item.copy(
                msg =
                    item.msg.mapBlocks { block ->
                        val text = (block as? Block.Text)?.text ?: return@mapBlocks listOf(block)
                        if (text.length <= cfg.maxChars) return@mapBlocks listOf(block)
                        val omitted = text.length - cfg.headChars - cfg.tailChars
                        val stub =
                            prompts
                                .render(ID_STUB, mapOf("omitted" to omitted.toString(), "seq" to item.seq.toString()))
                                .getOrNull()
                                ?.text
                                ?: return@mapBlocks listOf(block)
                        listOf(
                            Block.Text(text.take(cfg.headChars)),
                            Block.Text(stub),
                            Block.Text(text.takeLast(cfg.tailChars)),
                        )
                    },
            )
        }
    }

    internal companion object {
        const val ID = "history.long_text_truncate"
        const val ID_STUB = "agent.history.truncated"
    }
}
