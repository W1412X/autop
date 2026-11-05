package com.example.autop.core.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * 配置树合并算法（docs/tech/02 §2.2）。
 *
 * ```
 * merge(base, overlay):
 *   for (k, v) in overlay:
 *     if v is JsonNull            → 删除 base[k]（恢复默认）
 *     elif v is JsonObject && base[k] is JsonObject → 递归合并
 *     else                        → base[k] = v   // 数组与标量整体替换
 * ```
 */
public fun merge(
    base: JsonObject,
    overlay: JsonObject,
): JsonObject {
    val result = LinkedHashMap<String, JsonElement>(base.size + overlay.size)
    result.putAll(base)
    for ((key, value) in overlay) {
        val existing = result[key]
        when {
            value is JsonNull -> result.remove(key)
            value is JsonObject && existing is JsonObject -> result[key] = merge(existing, value)
            else -> result[key] = value
        }
    }
    return JsonObject(result)
}

/** 取 [tree] 中 [path]（点分隔段）处的节点，任一段缺失返回 null。 */
public fun resolvePath(
    tree: JsonObject,
    path: String,
): JsonElement? {
    var current: JsonElement = tree
    for (segment in path.split(PATH_SEPARATOR)) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}

/** 在 [tree] 的副本中把 [path]（点分隔段）处设为 [value]，返回新树。 */
public fun setPath(
    tree: JsonObject,
    path: String,
    value: JsonElement,
): JsonObject {
    val segments = path.split(PATH_SEPARATOR)
    return setPathRecursive(tree, segments, 0, value)
}

/**
 * 相对 [defaults] 的递归差异：只保留值不同的叶子，嵌套对象下钻后为空则整键省略；
 * [defaults] 中缺失于 [actual] 的键记为 `JsonNull`。结构与配置树同形。
 */
public fun diffJson(
    defaults: JsonObject,
    actual: JsonObject,
): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, value) in actual) {
        val base = defaults[key]
        when {
            base == null -> out[key] = value
            base is JsonObject && value is JsonObject -> {
                val nested = diffJson(base, value)
                if (nested.isNotEmpty()) out[key] = nested
            }
            base != value -> out[key] = value
        }
    }
    for (key in defaults.keys) {
        if (!actual.containsKey(key)) out[key] = JsonNull
    }
    return JsonObject(out)
}

private fun setPathRecursive(
    tree: JsonObject,
    segments: List<String>,
    index: Int,
    value: JsonElement,
): JsonObject {
    val key = segments[index]
    val updated: JsonElement =
        if (index == segments.lastIndex) {
            value
        } else {
            val child = tree[key] as? JsonObject ?: JsonObject(emptyMap())
            setPathRecursive(child, segments, index + 1, value)
        }
    val result = LinkedHashMap<String, JsonElement>(tree.size + 1)
    result.putAll(tree)
    result[key] = updated
    return JsonObject(result)
}

private const val PATH_SEPARATOR = '.'
