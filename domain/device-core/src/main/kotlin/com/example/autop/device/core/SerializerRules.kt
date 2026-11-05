/**
 * 序列化规则（docs/design/observation-modes-coordinate-actions §3.3）：由 device-impl 从
 * `assets/rules/serializer_rules.json` 读取后构造，core 只接收数据对象。
 */
package com.example.autop.device.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 类名后缀 → 标签名（最长后缀匹配）。 */
@Serializable
public data class TagRule(
    @SerialName("classSuffix") val classSuffix: String,
    @SerialName("tag") val tag: String,
)

/** 未命中 tags 时的默认标签名。 */
@Serializable
public data class DefaultTag(
    @SerialName("interactive") val interactive: String,
    @SerialName("label") val label: String,
    @SerialName("container") val container: String,
)

/** 序列化规则文件的数据对象。 */
@Serializable
public data class SerializerRules(
    @SerialName("schemaVersion") val schemaVersion: Int,
    /** 类名后缀 → 标签名；三种观测模式共用。 */
    @SerialName("tags") val tags: List<TagRule>,
    /** 视觉/llm 模式可用的标签词表（llm 解析器的 `tag` 枚举，§4.3）。 */
    @SerialName("visualTags") val visualTags: List<String> = emptyList(),
    /** 未命中 tags 时的默认标签名。 */
    @SerialName("defaultTag") val defaultTag: DefaultTag? = null,
    /** 窗口类型名 → 标签（空串表示不显示）。 */
    @SerialName("windowTypeLabels") val windowTypeLabels: Map<String, String>,
    /** 命中即视为语义贫乏候选的类名（docs/tech/05 §3.7）。 */
    @SerialName("semanticPoorClasses") val semanticPoorClasses: List<String>,
    /** viewId 短名要去掉的常见前缀（iv_/btn_/img_/tv_/ll_/rl_ 等）。 */
    @SerialName("viewIdPrefixes") val viewIdPrefixes: List<String> = emptyList(),
    /** viewId 短名噪声正则（命中即丢弃，不输出短名）。 */
    @SerialName("viewIdNoise") val viewIdNoise: List<String> = emptyList(),
    /** 稳定键 label 段要剔除的易变内容正则。 */
    @SerialName("volatileLabelPatterns") val volatileLabelPatterns: List<String> = emptyList(),
    /** 视觉建树保留的 CONTAINER 类名后缀（默认 tabbar）。 */
    @SerialName("keepContainerTags") val keepContainerTags: List<String> = listOf("tabbar"),
)
