/**
 * 观测 JSON 的协议常量（docs/design/observation-modes-coordinate-actions §3）：
 * 字段名、act/st 词与模板 id 集中在这里，改词只改这里。
 */
package com.example.autop.device.core

/** 协议常量表。 */
public object ScreenJsonWire {
    // 顶层
    public const val ROOT_SCREEN: String = "screen"
    public const val ROOT_DIFF: String = "diff"
    public const val FIELD_VERSION: String = "v"
    public const val FIELD_MODE: String = "mode"
    public const val FIELD_UNTRUSTED: String = "untrusted"
    public const val FIELD_COORD: String = "coord"
    public const val FIELD_FIELDS: String = "fields"
    public const val FIELD_WINDOWS: String = "windows"
    public const val FIELD_QUALITY: String = "quality"
    public const val FIELD_CACHED: String = "cached"

    // 窗口
    public const val FIELD_APP: String = "app"
    public const val FIELD_PAGE: String = "page"
    public const val FIELD_TYPE: String = "type"
    public const val FIELD_ACTIVE: String = "active"
    public const val FIELD_FOCUS: String = "focus"
    public const val FIELD_COLLAPSED: String = "collapsed"
    public const val FIELD_REDACTED: String = "redacted"
    public const val FIELD_KIDS: String = "kids"

    // 节点
    public const val FIELD_TAG: String = "tag"
    public const val FIELD_ID: String = "id"
    public const val FIELD_TEXT: String = "text"
    public const val FIELD_ICON: String = "icon"
    public const val FIELD_DESC: String = "desc"
    public const val FIELD_HINT: String = "hint"
    public const val FIELD_STATE: String = "state"
    public const val FIELD_CLS: String = "cls"
    public const val FIELD_VID: String = "vid"
    public const val FIELD_ACT: String = "act"
    public const val FIELD_ST: String = "st"
    public const val FIELD_BOUNDS: String = "bounds"
    public const val FIELD_AT: String = "at"
    public const val FIELD_MORE: String = "more"

    // 质量
    public const val FIELD_NODES: String = "nodes"
    public const val FIELD_INTERACTIVE: String = "interactive"
    public const val FIELD_DEGENERATE: String = "degenerate"
    public const val FIELD_POOR: String = "poor"
    public const val FIELD_INSUFFICIENT: String = "insufficient"
    public const val FIELD_SUGGEST: String = "suggest"
    public const val FIELD_TRUNCATED: String = "truncated"

    // 信息不足原因码（quality.insufficient 的取值）
    public const val INSUFFICIENT_NO_APP_WINDOW: String = "no_app_window"
    public const val INSUFFICIENT_FEW_NODES: String = "few_nodes"
    public const val INSUFFICIENT_SEMANTIC_POOR: String = "semantic_poor"
    public const val INSUFFICIENT_NO_INTERACTIVE: String = "no_interactive"

    // 差量
    public const val FIELD_ADDED: String = "added"
    public const val FIELD_CHANGED: String = "changed"
    public const val FIELD_REMOVED: String = "removed"

    // act 词
    public const val ACT_CLICK: String = "click"
    public const val ACT_LONG_PRESS: String = "longpress"
    public const val ACT_EDIT: String = "edit"
    public const val ACT_SCROLL: String = "scroll"

    // st 词
    public const val ST_CHECKED: String = "checked"
    public const val ST_UNCHECKED: String = "unchecked"
    public const val ST_SELECTED: String = "selected"
    public const val ST_DISABLED: String = "disabled"
    public const val ST_PASSWORD: String = "password"
    public const val ST_HEADING: String = "heading"

    // 命中回执
    public const val ROOT_HIT: String = "hit"
    public const val ROOT_NODE: String = "node"
    public const val FIELD_FROM: String = "from"
    public const val FIELD_NONE: String = "none"

    // 模板
    public const val PROMPT_COORD: String = "device.observation.coord"
    public const val PROMPT_INSUFFICIENT: String = "device.observe.insufficient"
    public const val PROMPT_INSUFFICIENT_REASON_PREFIX: String = "device.observe.insufficient."
    public const val PROMPT_DIFF_NONE: String = "device.observe.diff_none"
    public const val PROMPT_HINT_MODE_UNAVAILABLE: String = "device.hint.mode_unavailable"

    /** 模式对应的节点字段集合（顶层 `fields`）。 */
    public fun fieldsOf(mode: com.example.autop.device.ObserveMode): List<String> =
        when (mode) {
            com.example.autop.device.ObserveMode.A11Y ->
                listOf(
                    FIELD_ID,
                    FIELD_TEXT,
                    FIELD_DESC,
                    FIELD_HINT,
                    FIELD_STATE,
                    FIELD_CLS,
                    FIELD_VID,
                    FIELD_ACT,
                    FIELD_ST,
                )
            com.example.autop.device.ObserveMode.VISUAL ->
                listOf(FIELD_TEXT, FIELD_ICON, FIELD_HINT, FIELD_STATE, FIELD_ACT, FIELD_ST)
        }
}
