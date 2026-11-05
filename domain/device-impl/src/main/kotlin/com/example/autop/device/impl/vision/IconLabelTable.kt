/**
 * 图标显示名表（vp9 §5.4）：`rules/icon_labels.json` 的 100 图标类 → 中文显示名；
 * 未收录的键直接显示键名（调用方记 debug）。纯 Kotlin 解析，IO 由调用方注入文本。
 */
package com.example.autop.device.impl.vision

import com.example.autop.core.common.AutopJson
import kotlinx.serialization.Serializable

/** icon_labels.json 结构（vp9 §5.4）。 */
@Serializable
internal data class IconLabelsFile(
    val schemaVersion: Int = 1,
    val reject: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
)

/** 图标显示名表。 */
internal class IconLabelTable(
    private val labels: Map<String, String>,
    val reject: Set<String>,
) {
    /** 显示名：未收录的键直接显示键名（§5.4）。 */
    fun displayOf(key: String): String = labels[key] ?: key

    companion object {
        /** 解析 icon_labels.json 文本；非法返回空表（全部按键名显示、无拒识类）。 */
        fun parse(text: String): IconLabelTable =
            runCatching { AutopJson.default.decodeFromString(IconLabelsFile.serializer(), text) }
                .getOrNull()
                ?.let { IconLabelTable(it.labels, it.reject.toSet()) }
                ?: IconLabelTable(emptyMap(), emptySet())
    }
}
