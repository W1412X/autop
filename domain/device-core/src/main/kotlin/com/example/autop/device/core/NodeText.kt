/**
 * 节点文本与标签（docs/tech/05 §3.2 步骤 7–8、docs/design/observation-modes-coordinate-actions §3.3）：
 * 分类、标签形态、标签名映射与文字兜底链。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot

/** 节点分类（步骤 3）。 */
public enum class NodeCategory {
    INTERACTIVE,
    LABELED,
    CONTAINER,
}

/** 标签形态：文本 / 输入框提示语 / viewId 短名 / 空。 */
public enum class LabelKind {
    TEXT,
    HINT,
    VIEW_ID,
    EMPTY,
}

/** 节点标签：内容 + 形态。 */
public data class NodeLabel(
    val text: String,
    val kind: LabelKind,
)

/** 分类与文字规则。 */
public object NodeText {
    /** 步骤 3：interactive / labeled / container。 */
    public fun categoryOf(node: NodeSnapshot): NodeCategory {
        if (node.flags and NodeFlags.ENABLED != 0 && node.flags and INTERACTIVE_ANY != 0) {
            return NodeCategory.INTERACTIVE
        }
        val labeled =
            !node.text.isNullOrBlank() ||
                !node.contentDesc.isNullOrBlank() ||
                (node.flags and NodeFlags.EDITABLE != 0 && !node.hint.isNullOrBlank())
        return if (labeled) NodeCategory.LABELED else NodeCategory.CONTAINER
    }

    /**
     * 步骤 8 标签兜底链：text → contentDesc → 合并的后代标签 → (EDITABLE) hint →
     * viewId 短名 → 空；PASSWORD 不输出文字。
     */
    public fun labelOf(
        node: NodeSnapshot,
        extras: List<String>,
        rules: SerializerRules,
        config: SerializerConfig,
    ): NodeLabel {
        if (node.flags and NodeFlags.PASSWORD != 0) return NodeLabel("", LabelKind.EMPTY)
        val text = node.text
        val desc = node.contentDesc
        val hint = node.hint
        val own =
            when {
                !text.isNullOrBlank() -> normalize(text)
                !desc.isNullOrBlank() -> normalize(desc)
                else -> null
            }
        val merged =
            (listOfNotNull(own) + extras.map { normalize(it) })
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        return when {
            merged.isNotEmpty() -> {
                val joined = merged.joinToString(LABEL_JOIN)
                NodeLabel(
                    if (extras.isEmpty()) truncate(joined, config) else truncateWide(joined, config),
                    LabelKind.TEXT,
                )
            }
            node.flags and NodeFlags.EDITABLE != 0 && !hint.isNullOrBlank() ->
                NodeLabel(truncate(normalize(hint), config), LabelKind.HINT)
            else -> {
                val shortName = ViewIdHumanizer.shortName(node.viewId, rules)
                if (shortName != null && node.flags and INTERACTIVE_ANY != 0) {
                    NodeLabel(shortName, LabelKind.VIEW_ID)
                } else {
                    NodeLabel("", LabelKind.EMPTY)
                }
            }
        }
    }

    /** 标签名：className 后缀最长匹配 rules `tags`，未命中按 defaultTag 分类。 */
    public fun tagOf(
        node: NodeSnapshot,
        category: NodeCategory,
        label: String,
        rules: SerializerRules,
    ): String {
        val className = node.className.orEmpty()
        rules.tags
            .filter { className.endsWith(it.classSuffix) }
            .maxByOrNull { it.classSuffix.length }
            ?.let { return it.tag }
        val fallback = rules.defaultTag ?: FALLBACK_TAG
        return when {
            category == NodeCategory.INTERACTIVE -> fallback.interactive
            label.isNotEmpty() -> fallback.label
            else -> fallback.container
        }
    }

    /** 标签名（仅类名匹配，供视觉 keepContainerTags 判定）。 */
    public fun mappedTag(
        className: String?,
        rules: SerializerRules,
    ): String? =
        rules.tags
            .filter { className.orEmpty().endsWith(it.classSuffix) }
            .maxByOrNull { it.classSuffix.length }
            ?.tag

    /** stateDescription 截断口径（textMaxChars，≤0 无限制）。 */
    public fun stateOf(
        node: NodeSnapshot,
        config: SerializerConfig,
    ): String = truncate(node.stateDesc.orEmpty().trim(), config)

    /** 文字规范化：换行压成空格。 */
    public fun normalize(text: String): String = text.replace('\n', ' ').replace('\r', ' ')

    /** 按 textMaxChars 截断；≤0 无限制。 */
    public fun truncate(
        text: String,
        config: SerializerConfig,
    ): String {
        val trimmed = text.trim()
        if (config.textMaxChars <= 0) return trimmed
        return if (trimmed.length <= config.textMaxChars) trimmed else trimmed.take(config.textMaxChars) + ELLIPSIS
    }

    /** 合并标签截断：上限 textMaxChars × 2；≤0 无限制。 */
    public fun truncateWide(
        text: String,
        config: SerializerConfig,
    ): String {
        if (config.textMaxChars <= 0) return text
        val limit = config.textMaxChars * 2
        return if (text.length <= limit) text else text.take(limit) + ELLIPSIS
    }

    private const val LABEL_JOIN = " · "
    private const val ELLIPSIS = "…"

    private val FALLBACK_TAG = DefaultTag(interactive = "ctl", label = "text", container = "group")

    /** 交互位并集（含 SCROLLABLE）。 */
    public const val INTERACTIVE_ANY: Int =
        NodeFlags.CLICKABLE or NodeFlags.LONG_CLICKABLE or NodeFlags.EDITABLE or
            NodeFlags.SCROLLABLE or NodeFlags.CHECKABLE
}
