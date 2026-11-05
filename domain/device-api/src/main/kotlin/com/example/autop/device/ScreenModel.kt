/**
 * 统一屏幕模型（docs/design/observation-modes-coordinate-actions §3）：三种观测模式共用，
 * 坐标为内容区 0–1000 归一化整数。JSON 渲染、差量、命中回执与 llm 解析都围绕本模型。
 */
package com.example.autop.device

import kotlinx.serialization.Serializable

/** 屏幕上的一个点（0–1000 归一化坐标）。 */
@Serializable
public data class Point(
    val x: Int,
    val y: Int,
)

/** 观测方式（稳定 id `a11y` / `visual` / `llm`）。 */
@Serializable
public enum class ObserveMode(
    public val id: String,
) {
    A11Y("a11y"),
    VISUAL("visual"),
    LLM("llm"),
    ;

    public companion object {
        /** 按稳定 id 解析；未知值返回 null（工具参数自行报 INVALID_ARGUMENT）。 */
        public fun parse(value: String?): ObserveMode? = entries.firstOrNull { it.id == value }
    }
}

/** 统一节点：字段按 §3.3 表格顺序声明；空值不输出。 */
@Serializable
public data class ScreenNode(
    val tag: String,
    val text: String? = null,
    val icon: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val state: String? = null,
    val cls: String? = null,
    val vid: String? = null,
    val act: List<String> = emptyList(),
    val st: List<String> = emptyList(),
    /** 归一化外接框（0–1000，裁到基准区）。 */
    val bounds: Rect,
    /** 推荐操作点（0–1000）；仅 [act] 非空时有值。 */
    val at: Point? = null,
    /** 列表折叠提示原文（不含前导“…”）。 */
    val more: String? = null,
    val kids: List<ScreenNode> = emptyList(),
    /** 内部稳定键（差量配对用，不对外输出）。 */
    val key: String = "",
)

/** 统一窗口。 */
@Serializable
public data class ScreenWindow(
    val app: String,
    val page: String? = null,
    val type: String? = null,
    val active: Boolean = false,
    /** 焦点输入框 bounds（0–1000）。 */
    val focus: Rect? = null,
    val collapsed: Boolean = false,
    val redacted: Boolean = false,
    val kids: List<ScreenNode> = emptyList(),
)

/** 质量信号（§3.5）。 */
@Serializable
public data class ScreenQuality(
    val nodes: Int,
    val interactive: Int,
    /** 仅 a11y：有效节点数不足。 */
    val degenerate: Boolean = false,
    /** 仅 a11y：语义贫乏。 */
    val poor: Boolean = false,
    /** 仅 visual/llm：存疑节点数。 */
    val uncertain: Int? = null,
    /** 建议换用的模式；null=不输出。 */
    val suggest: ObserveMode? = null,
)

/** 一次观测的统一屏幕模型。 */
@Serializable
public data class ScreenModel(
    val mode: ObserveMode,
    val windows: List<ScreenWindow>,
    val quality: ScreenQuality,
    /** llm 命中缓存时为 true。 */
    val cached: Boolean = false,
) {
    /** 全树节点数（含嵌套）。 */
    public val nodeCount: Int
        get() = quality.nodes

    /** 深度优先展开全部节点。 */
    public fun flatten(): List<ScreenNode> = windows.flatMap { it.kids }.flatMap { flatten(it) }

    private fun flatten(node: ScreenNode): List<ScreenNode> = listOf(node) + node.kids.flatMap { flatten(it) }
}
