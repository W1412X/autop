/**
 * 应用名称匹配（intent-tool §4.7）：纯函数，供 `open_app` 与 `intent` 的 share/settings 共用。
 */
package com.example.autop.device.core

import com.example.autop.device.AppInfo

/** 应用名称匹配的结果。 */
public sealed interface AppMatch {
    /** 唯一命中。 */
    public data class Found(
        val app: AppInfo,
    ) : AppMatch

    /** 多个候选，至多 [AppMatcher.MAX_CANDIDATES] 个，按匹配强度排序。 */
    public data class Ambiguous(
        val candidates: List<AppInfo>,
    ) : AppMatch

    /** 没有命中。 */
    public data object NotFound : AppMatch
}

/** 应用名称匹配（纯函数，无 Android 依赖）。 */
public object AppMatcher {
    /** 歧义时返回的候选上限。 */
    public const val MAX_CANDIDATES: Int = 5

    /**
     * 名称匹配排序：完全相等 > 前缀 > 包含，同级保持原顺序。
     * 与 `open_app` 原先的 `matchApps` 行为完全一致。
     */
    public fun rank(
        all: List<AppInfo>,
        name: String,
    ): List<AppInfo> {
        val exact = all.filter { it.label == name }
        val prefix = all.filter { it.label.startsWith(name) && it.label != name }
        val contains = all.filter { it.label.contains(name) && !it.label.startsWith(name) }
        return exact + prefix + contains
    }

    /**
     * 按 [query] 在 [all] 中定位应用：包名完全相等直接命中；否则取 [rank] 结果，
     * 空为未找到，只有 1 个或名称完全相等的恰好 1 个为命中，其余为歧义。
     */
    public fun match(
        query: String,
        all: List<AppInfo>,
    ): AppMatch {
        all.firstOrNull { it.packageName == query }?.let { return AppMatch.Found(it) }
        val ranked = rank(all, query)
        if (ranked.isEmpty()) return AppMatch.NotFound
        if (ranked.size == 1) return AppMatch.Found(ranked.first())
        val exact = ranked.filter { it.label == query }
        if (exact.size == 1) return AppMatch.Found(exact.first())
        return AppMatch.Ambiguous(ranked.take(MAX_CANDIDATES))
    }
}
