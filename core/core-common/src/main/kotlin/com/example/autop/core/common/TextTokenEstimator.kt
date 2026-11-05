package com.example.autop.core.common

import kotlin.math.roundToInt

/**
 * 文本 token 数估算（docs/tech/02，WP1 纯函数）。
 *
 * 用于上下文预算与压缩触发，不需要精确——只需稳定、可比。参数来自配置
 * （`agent.tokens.cjkFactor`、`agent.tokens.charsPerToken`，docs/tech/09 §1.1），
 * 由调用方传入，本对象不读配置。
 */
public object TextTokenEstimator {
    /**
     * 估算 [text] 的 token 数：CJK 字符按 [cjkFactor] 个字符/token 加权，
     * 其余字符按 [charsPerToken] 个字符/token；四舍五入后至少为 0。
     */
    public fun estimate(
        text: String,
        cjkFactor: Double,
        charsPerToken: Double,
    ): Int {
        if (text.isEmpty()) return 0
        require(charsPerToken > 0) { "charsPerToken must be positive" }
        require(cjkFactor > 0) { "cjkFactor must be positive" }
        var cjkChars = 0
        var otherChars = 0
        for (ch in text) {
            if (BigramTokenizer.isCjkChar(ch)) {
                cjkChars++
            } else {
                otherChars++
            }
        }
        val cjkTokens = cjkChars / cjkFactor
        val otherTokens = otherChars / charsPerToken
        return (cjkTokens + otherTokens).roundToInt()
    }
}
