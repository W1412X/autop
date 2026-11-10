/**
 * 节点稳定键（docs/tech/05 §3.4、新方案 §2.9）：`windowPackage | viewId | role | label前N字 | 窗口内位置`。
 * label 段先按规则 `volatileLabelPatterns` 去掉易变部分（数字、时间、角标），再截前 N 字：
 * 无 viewId 截 20 字，有 viewId 只截 8 字（viewId 已承担稳定身份，降低文字变化影响）。
 * 位置段见 [SnapshotIndex.positionOf]（`windowId.子序号路径`），保证一屏内一键一节点。
 * [NodeTreeBuilder] 生成，差量配对按它对齐节点，保证同一节点得到同一稳定键。
 */
package com.example.autop.device.core

import com.example.autop.device.NodeSnapshot

/** 稳定键分隔符。 */
public const val KEY_SEPARATOR: String = " | "

/** 稳定键中 label 的保留长度（无 viewId）。 */
private const val KEY_LABEL_CHARS: Int = 20

/** 有 viewId 时 label 的保留长度（新方案 §2.9）。 */
private const val KEY_LABEL_VIEW_ID_CHARS: Int = 8

/** 规则 `volatileLabelPatterns`（字符串）→ 预编译正则；调用方每次序列化编译一份。 */
public fun compileVolatilePatterns(patterns: List<String>): List<Regex> = patterns.map(::Regex)

/** 计算节点的稳定键。 */
public fun stableNodeKey(
    windowPackage: String?,
    node: NodeSnapshot,
    role: String,
    label: String,
    index: SnapshotIndex,
    volatilePatterns: List<Regex> = emptyList(),
): String {
    val stable = stripVolatile(label, volatilePatterns)
    val keep = if (node.viewId.isNullOrBlank()) KEY_LABEL_CHARS else KEY_LABEL_VIEW_ID_CHARS
    return listOf(
        windowPackage.orEmpty(),
        node.viewId.orEmpty(),
        role,
        stable.take(keep),
        index.positionOf(node.idx),
    ).joinToString(KEY_SEPARATOR)
}

/** 新方案 §2.9：按易变模式抠除 label 中的易变片段（数字、时间、角标“99+”）。 */
internal fun stripVolatile(
    label: String,
    patterns: List<Regex>,
): String = patterns.fold(label) { acc, pattern -> pattern.replace(acc, "") }
