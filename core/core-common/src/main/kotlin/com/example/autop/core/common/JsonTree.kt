package com.example.autop.core.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON 树（N210）：工具测试输出与参数的树级可视化数据模型。
 * 解析在 core-common（纯 JVM 可单测），渲染在 core-ui。
 */
public data class JsonTree(
    /** 根节点；为 null 表示文本不是 JSON（展示端退回纯文本）。 */
    val root: JsonTreeNode?,
    /** JSON 之后的尾随文本（如 observe 的建议行）。 */
    val trailing: String,
)

/** JSON 树节点。 */
public sealed interface JsonTreeNode {
    /** 键名（对象成员为键名，数组元素为 "[下标]"，根节点为空）。 */
    public val key: String

    /** 子节点（叶子节点为空列表）。 */
    public val children: List<JsonTreeNode>

    /** 标量叶子。 */
    public data class Leaf(
        override val key: String,
        /** 值的 JSON 字面量文本（字符串带引号）。 */
        val value: String,
    ) : JsonTreeNode {
        override val children: List<JsonTreeNode> get() = emptyList()
    }

    /** 容器（对象或数组）。 */
    public data class Branch(
        override val key: String,
        /** 容器类型。 */
        val kind: Kind,
        override val children: List<JsonTreeNode>,
    ) : JsonTreeNode {
        /** 容器类型。 */
        public enum class Kind { OBJECT, ARRAY }
    }
}

/**
 * JSON 文本 → 树模型（N210）。
 * 工具输出常是「JSON + 尾随说明」（如 observe 的建议行），故只取首个完整 JSON 值，
 * 余下文本作为 [JsonTree.trailing] 保留。
 */
public object JsonTreeParser {
    /** 解析文本；不是 JSON 时返回根节点为 null 的树。 */
    public fun parse(text: String): JsonTree {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return JsonTree(null, "")
        val end = jsonValueEnd(trimmed)
        if (end < 0) return JsonTree(null, "")
        val element = runCatching { AutopJson.default.parseToJsonElement(trimmed.substring(0, end)) }.getOrNull()
        if (element == null) return JsonTree(null, "")
        return JsonTree(toNode("", element), trimmed.substring(end).trim())
    }

    /** 首个完整 JSON 值的结束下标（含），未闭合返回 -1。 */
    private fun jsonValueEnd(text: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            if (ch == '"') {
                inString = true
            } else if (ch == '{' || ch == '[') {
                depth++
            } else if (ch == '}' || ch == ']') {
                depth--
                if (depth == 0) return i + 1
                if (depth < 0) return -1
            }
        }
        return -1
    }

    /** JsonElement → 树节点。 */
    private fun toNode(
        key: String,
        element: JsonElement,
    ): JsonTreeNode =
        when (element) {
            is JsonObject ->
                JsonTreeNode.Branch(
                    key = key,
                    kind = JsonTreeNode.Branch.Kind.OBJECT,
                    children = element.map { (name, child) -> toNode(name, child) },
                )
            is JsonArray ->
                JsonTreeNode.Branch(
                    key = key,
                    kind = JsonTreeNode.Branch.Kind.ARRAY,
                    children = element.mapIndexed { index, child -> toNode("[$index]", child) },
                )
            else -> JsonTreeNode.Leaf(key = key, value = element.toString())
        }
}
