@file:Suppress("LoopWithTooManyJumpStatements", "MagicNumber") // 目标匹配的过滤循环；0–1000 为归一化坐标协议

package com.example.autop.flow.core.screen

import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.flow.MatchMode
import com.example.autop.flow.Pick
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy

/** 一个命中：节点 + 推荐操作点（0–1000）。 */
public data class TargetHit(
    val node: ScreenNode?,
    val point: Point,
    val viaFallback: Boolean = false,
)

/** 目标解析结果。 */
public sealed interface TargetMatch {
    public data class Found(
        val hits: List<TargetHit>,
    ) : TargetMatch

    public data object NotFound : TargetMatch

    public data class Ambiguous(
        val count: Int,
    ) : TargetMatch
}

/**
 * 目标描述 → 屏幕节点（docs/design/flows.md §5）。文字匹配默认 segment：
 * 节点文字按 " · " 分段后与值逐段相等（兼容"标题 · 摘要"类拼接文字）。
 */
public object TargetMatcher {
    private const val SEGMENT_SEPARATOR = " · "
    private const val COORD_MAX = 1000
    private const val CENTER = 500

    /** [resolveText] 用于把模板化的 value 渲染后传入；这里只做匹配。 */
    public fun resolve(
        screen: ScreenModel,
        target: Target,
        value: String?,
    ): TargetMatch {
        val entries = collect(screen)
        val hits = findHits(entries, target, value)
        if (hits.isEmpty()) return fallback(entries, target)
        return pick(hits, target)
    }

    /** 带父链的节点条目。 */
    private data class Entry(
        val node: ScreenNode,
        val parent: Entry?,
    )

    private fun collect(screen: ScreenModel): List<Entry> {
        val out = mutableListOf<Entry>()

        fun walk(
            node: ScreenNode,
            parent: Entry?,
        ) {
            val e = Entry(node, parent)
            out += e
            node.kids.forEach { walk(it, e) }
        }
        screen.windows.forEach { w -> w.kids.forEach { walk(it, null) } }
        return out
    }

    private fun findHits(
        entries: List<Entry>,
        target: Target,
        value: String?,
    ): List<ScreenNode> {
        val scope = scopeOf(entries, target)
        val actionable = target.actionable ?: true
        val seen = LinkedHashSet<ScreenNode>()
        for (e in scope) {
            if (!matches(e.node, target, value)) continue
            val region = target.region
            if (region != null && !inRegion(e.node, region)) continue
            val chosen = if (actionable) climbToActionable(e) else e.node
            if (chosen != null) seen += chosen
        }
        return seen.toList()
    }

    private fun scopeOf(
        entries: List<Entry>,
        target: Target,
    ): List<Entry> {
        val within = target.within ?: return entries
        val container =
            findHits(entries, within.copy(actionable = false), within.value).firstOrNull() ?: return emptyList()
        return entries.filter { isDescendant(it, container) }
    }

    private fun isDescendant(
        entry: Entry,
        ancestor: ScreenNode,
    ): Boolean {
        var cur: Entry? = entry.parent
        while (cur != null) {
            if (cur.node === ancestor) return true
            cur = cur.parent
        }
        return false
    }

    private fun climbToActionable(entry: Entry): ScreenNode? {
        var cur: Entry? = entry
        while (cur != null) {
            if (cur.node.act.isNotEmpty()) return cur.node
            cur = cur.parent
        }
        return null
    }

    private fun matches(
        node: ScreenNode,
        target: Target,
        value: String?,
    ): Boolean {
        if (target.tag != null && node.tag != target.tag) return false
        if (target.by == TargetBy.TAG) return value == null || node.tag == value
        val field = fieldOf(node, target.by)
        if (value == null) return target.tag != null
        if (field.isNullOrEmpty()) return false
        return textMatches(field, value, target.match)
    }

    private fun fieldOf(
        node: ScreenNode,
        by: TargetBy,
    ): String? =
        when (by) {
            TargetBy.TEXT -> node.text
            TargetBy.ICON -> node.icon
            TargetBy.DESC -> node.desc
            TargetBy.HINT -> node.hint
            TargetBy.TAG -> node.tag
        }

    private fun textMatches(
        field: String,
        value: String,
        mode: MatchMode,
    ): Boolean =
        when (mode) {
            MatchMode.EQUALS -> field.trim() == value.trim()
            MatchMode.SEGMENT ->
                field.trim() == value.trim() || field.split(SEGMENT_SEPARATOR).any { it.trim() == value.trim() }
            MatchMode.CONTAINS -> field.contains(value, ignoreCase = true)
            MatchMode.STARTS_WITH -> field.startsWith(value, ignoreCase = true)
            MatchMode.REGEX -> runCatching { Regex(value).containsMatchIn(field) }.getOrDefault(false)
        }

    private fun inRegion(
        node: ScreenNode,
        region: List<Int>,
    ): Boolean {
        if (region.size != 4) return true
        val cx = (node.bounds.l + node.bounds.r) / 2
        val cy = (node.bounds.t + node.bounds.b) / 2
        return cx in region[0]..region[2] && cy in region[1]..region[3]
    }

    private fun pick(
        hits: List<ScreenNode>,
        target: Target,
    ): TargetMatch {
        val ordered = hits.sortedWith(compareBy({ it.bounds.t }, { it.bounds.l }))
        val chosen =
            when (target.pick) {
                Pick.UNIQUE -> if (ordered.size == 1) ordered else return TargetMatch.Ambiguous(ordered.size)
                Pick.FIRST -> ordered.take(1)
                Pick.LAST -> ordered.takeLast(1)
                Pick.NTH -> listOfNotNull(ordered.getOrNull((target.nth ?: 1) - 1))
                Pick.NEAREST -> listOf(ordered.minByOrNull { distanceToCenter(it.bounds) } ?: ordered.first())
                Pick.ALL -> ordered
            }
        if (chosen.isEmpty()) return TargetMatch.NotFound
        return TargetMatch.Found(chosen.map { TargetHit(it, pointOf(it)) })
    }

    private fun distanceToCenter(r: Rect): Int {
        val dx = (r.l + r.r) / 2 - CENTER
        val dy = (r.t + r.b) / 2 - CENTER
        return dx * dx + dy * dy
    }

    /** 推荐操作点；缺失时取外接框中心。 */
    public fun pointOf(node: ScreenNode): Point =
        node.at ?: Point((node.bounds.l + node.bounds.r) / 2, (node.bounds.t + node.bounds.b) / 2)

    private fun fallback(
        entries: List<Entry>,
        target: Target,
    ): TargetMatch {
        val fb = target.fallback ?: return TargetMatch.NotFound
        if (fb.at.size != 2) return TargetMatch.NotFound
        val p = Point(fb.at[0].coerceIn(0, COORD_MAX), fb.at[1].coerceIn(0, COORD_MAX))
        if (fb.requireTag != null || fb.requireText != null) {
            val under = smallestAt(entries, p) ?: return TargetMatch.NotFound
            if (fb.requireTag != null && under.tag != fb.requireTag) return TargetMatch.NotFound
            val needText = fb.requireText
            if (needText != null && under.text?.contains(needText) != true) return TargetMatch.NotFound
        }
        return TargetMatch.Found(listOf(TargetHit(null, p, viaFallback = true)))
    }

    private fun smallestAt(
        entries: List<Entry>,
        p: Point,
    ): ScreenNode? =
        entries
            .map { it.node }
            .filter { p.x in it.bounds.l..it.bounds.r && p.y in it.bounds.t..it.bounds.b }
            .minByOrNull { it.bounds.area }
}
