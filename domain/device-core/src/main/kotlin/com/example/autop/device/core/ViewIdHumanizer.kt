/**
 * viewId 短名（屏幕观测文本新方案 §2.2）：`:id/` 后段 → snake/camel 切分 → 去常见前缀 →
 * 噪声过滤。纯函数；前缀表与噪声正则来自规则文件（`viewIdPrefixes`/`viewIdNoise`）。
 * 仅 schemaVersion ≥ 2 启用（ADR-0007 决策 9），v1 规则返回 null。
 */
package com.example.autop.device.core

/** viewId 短名推断。 */
public object ViewIdHumanizer {
    /** 短名（渲染为 `[短名]`）；不可用/噪声时 null。 */
    public fun shortName(
        viewId: String?,
        rules: SerializerRules,
    ): String? {
        val raw = if (rules.schemaVersion < 2) "" else viewId?.substringAfterLast('/')?.trim().orEmpty()
        val prefixes = rules.viewIdPrefixes.map { it.trim().lowercase().trimEnd('_') }.toHashSet()
        val name = splitTokens(raw).dropWhile { it.lowercase() in prefixes }.joinToString("")
        val noisy = rules.viewIdNoise.any { name.matches(Regex(it, RegexOption.IGNORE_CASE)) }
        return name.takeIf { it.isNotEmpty() && !noisy }
    }

    /** snake 与 camel 切分：先按 `_`/`-`/空格分段，再按大小写边界细分。 */
    private fun splitTokens(raw: String): List<String> =
        raw
            .split('_', '-', ' ')
            .flatMap { part ->
                part
                    .replace(CAMEL_LOWER_UPPER, " ")
                    .replace(CAMEL_ACRONYM, " ")
                    .split(' ')
            }.filter { it.isNotBlank() }

    private val CAMEL_LOWER_UPPER = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val CAMEL_ACRONYM = Regex("(?<=[A-Z])(?=[A-Z][a-z])")
}
