/**
 * 统一屏幕模型装配（docs/design/observation-modes-coordinate-actions §3）：
 * 扁平行 → 嵌套 `ScreenNode` 树，补 quality 与 suggest；视觉单子提升只在这里做。
 */
package com.example.autop.device.core

import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenQuality
import com.example.autop.device.ScreenWindow

/** 一个窗口的装配输入（无障碍/视觉）。 */
internal data class WindowDraft(
    val app: String,
    val page: String?,
    val type: String?,
    val active: Boolean,
    val focus: Rect?,
    val collapsed: Boolean,
    val redacted: Boolean,
    val rows: List<KeptNode>,
)

/** 统一模型装配。 */
public object ScreenModelBuilder {
    /** 扁平节点行 → 窗口模型（[drafts] 已按 Z 序排好）。 */
    internal fun fromDrafts(
        mode: ObserveMode,
        drafts: List<WindowDraft>,
        degenerate: Boolean,
        poor: Boolean,
        effectiveModes: List<ObserveMode>,
    ): ScreenModel =
        of(
            mode = mode,
            windows =
                drafts.map {
                    ScreenWindow(
                        app = it.app,
                        page = it.page,
                        type = it.type,
                        active = it.active,
                        focus = it.focus,
                        collapsed = it.collapsed,
                        redacted = it.redacted,
                        kids = if (it.redacted || it.collapsed) emptyList() else nodesOf(it.rows, mode),
                    )
                },
            degenerate = degenerate,
            poor = poor,
            effectiveModes = effectiveModes,
        )

    /** 已建好的窗口 + quality 统计（llm 解析器直接给树）。 */
    public fun of(
        mode: ObserveMode,
        windows: List<ScreenWindow>,
        degenerate: Boolean = false,
        poor: Boolean = false,
        effectiveModes: List<ObserveMode> = listOf(mode),
        cached: Boolean = false,
    ): ScreenModel {
        val nodes = windows.flatMap { it.kids }.flatMap { flatten(it) }
        val interactive = nodes.count { it.act.isNotEmpty() }
        val uncertain = if (mode == ObserveMode.A11Y) null else nodes.count { ST_UNCERTAIN in it.st }
        val a11y = mode == ObserveMode.A11Y
        val quality =
            ScreenQuality(
                nodes = nodes.size,
                interactive = interactive,
                degenerate = a11y && degenerate,
                poor = a11y && poor,
                uncertain = uncertain,
                suggest = suggestOf(mode, degenerate || poor || interactive == 0, effectiveModes),
            )
        return ScreenModel(mode = mode, windows = windows, quality = quality, cached = cached)
    }

    /** 生效模式列表中当前模式之后的第一个模式（§3.5）；没有则 null。 */
    private fun suggestOf(
        mode: ObserveMode,
        insufficient: Boolean,
        effectiveModes: List<ObserveMode>,
    ): ObserveMode? {
        if (!insufficient) return null
        val pos = effectiveModes.indexOf(mode)
        return if (pos >= 0) effectiveModes.getOrNull(pos + 1) else null
    }

    private fun flatten(node: ScreenNode): List<ScreenNode> = listOf(node) + node.kids.flatMap { flatten(it) }

    /** 行序列 → 节点树；折叠伪行转成父列表节点的 `more`。 */
    private fun nodesOf(
        rows: List<KeptNode>,
        mode: ObserveMode,
    ): List<ScreenNode> {
        val builders = buildRows(rows)
        val promoted = if (mode == ObserveMode.VISUAL) promoteSingles(builders) else builders
        return promoted.map { it.toNode() }
    }

    private fun buildRows(rows: List<KeptNode>): List<NodeBuilder> {
        val roots = mutableListOf<NodeBuilder>()
        val stack = ArrayDeque<NodeBuilder>()
        for (row in rows) {
            if (row.idx == FOLD_ROW_IDX) {
                val parent = stack.lastOrNull { it.row.depth == row.depth - 1 }
                if (parent != null && !row.more.isNullOrEmpty()) parent.more = row.more
                continue
            }
            while (stack.isNotEmpty() && stack.last().row.depth >= row.depth) stack.removeLast()
            val builder = NodeBuilder(row)
            if (stack.isEmpty()) roots += builder else stack.last().kids += builder
            stack.addLast(builder)
        }
        return roots
    }

    /**
     * 视觉单子提升（vp9 §1.3）：容器只有一个非交互叶子子节点，且容器自身 text/icon 都空时，
     * 把子的 text/icon 移到容器并删子（移动不是复制）。
     */
    private fun promoteSingles(nodes: List<NodeBuilder>): List<NodeBuilder> =
        nodes.map { container ->
            val kids = promoteSingles(container.kids)
            val only = kids.singleOrNull()
            if (only != null && promotable(container, only)) {
                container.text = only.text
                container.icon = only.icon
                container.kids = emptyList()
            } else {
                container.kids = kids
            }
            container
        }

    private fun promotable(
        container: NodeBuilder,
        child: NodeBuilder,
    ): Boolean =
        child.kids.isEmpty() &&
            child.row.act.isEmpty() &&
            container.text.isNullOrEmpty() &&
            container.icon.isNullOrEmpty()

    private class NodeBuilder(
        val row: KeptNode,
    ) {
        var text: String? = row.text.takeIf { it.isNotEmpty() }
        var icon: String? = row.icon?.takeIf { it.isNotEmpty() }
        var kids: List<NodeBuilder> = emptyList()
        var more: String? = row.more

        fun toNode(): ScreenNode =
            ScreenNode(
                tag = row.tag,
                text = text,
                icon = icon,
                desc = row.desc.takeIf { it.isNotEmpty() },
                hint = row.hint.takeIf { it.isNotEmpty() },
                state = row.state.takeIf { it.isNotEmpty() },
                cls = row.cls?.takeIf { it.isNotEmpty() },
                vid = row.vid?.takeIf { it.isNotEmpty() },
                act = row.act,
                st = row.st,
                bounds = row.bounds ?: EMPTY_BOUNDS,
                at = row.at,
                more = more,
                kids = kids.map { it.toNode() },
                key = row.key,
            )
    }

    private val EMPTY_BOUNDS: Rect = Rect(0, 0, 0, 0)

    private const val ST_UNCERTAIN: String = ScreenJsonWire.ST_UNCERTAIN
}
