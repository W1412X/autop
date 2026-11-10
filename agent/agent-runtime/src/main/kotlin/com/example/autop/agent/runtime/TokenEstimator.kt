/**
 * token 估算（docs/tech/04 §3.5）：文本按 CJK 加权、图片按固定值、消息按条加 overhead。
 * 只求稳定可比，不求精确；阈值判定见 TurnLoop（配合服务端 usage 取 max）。
 */
package com.example.autop.agent.runtime

import com.example.autop.core.config.AgentTokensSection
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolSchema
import kotlin.math.ceil

/** 按配置参数估算上下文 token 数。 */
internal class TokenEstimator(
    private val cfg: AgentTokensSection,
    /** `ModelProfile.quirks["imageTokens"]`，优先于配置（docs/tech/04 §3.5）。 */
    private val imageTokensQuirk: Int? = null,
) {
    /** 文本估算：CJK 字符按 `cjkFactor` token/字，其余按 `charsPerToken` 字/token，向上取整。 */
    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return ceil(cjk * cfg.cjkFactor + other / cfg.charsPerToken).toInt()
    }

    /** 单张图片的估算值。 */
    fun estimateImage(): Int = imageTokensQuirk ?: cfg.imageTokens

    /** `estimate(system, live, tools)`：system + 消息块 + 工具 schema JSON + 每条消息 overhead。 */
    fun estimate(
        system: List<SystemPart>,
        messages: List<Msg>,
        tools: List<ToolSchema>,
    ): Int {
        var total = 0
        for (part in system) total += estimateText(part.text)
        for (msg in messages) total += estimateMessage(msg)
        for (tool in tools) {
            total += estimateText(tool.name) + estimateText(tool.description) + estimateText(tool.parameters.toString())
        }
        return total
    }

    /** 单条消息：各块 + perMessageOverhead。 */
    fun estimateMessage(msg: Msg): Int {
        var total = cfg.perMessageOverhead
        for (block in msg.blocks) total += estimateBlock(block)
        return total
    }

    private fun estimateBlock(block: Block): Int =
        when (block) {
            is Block.Text -> estimateText(block.text)
            is Block.Image -> estimateImage()
            is Block.ToolCall -> estimateText(block.name) + estimateText(block.argsJson)
            is Block.ToolResult -> block.content.sumOf(::estimateBlock)
            is Block.Thinking -> estimateText(block.text)
        }

    /** CJK 判定（docs/tech/04 §3.5 的 7 个 Unicode 块；与 BigramTokenizer 的分词判定范围不同）。 */
    private fun isCjk(ch: Char): Boolean =
        when (Character.UnicodeBlock.of(ch)) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            -> true
            else -> false
        }
}
