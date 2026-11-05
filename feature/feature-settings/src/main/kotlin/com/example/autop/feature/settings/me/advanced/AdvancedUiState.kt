/**
 * 高级配置页状态（docs/tech/08 §4.5"高级配置"：配置项树，每项显示默认值/当前值，
 * 编辑 JSON 片段，校验失败显示错误；docs/standards/coding-standards §8）。
 */
package com.example.autop.feature.settings.me.advanced

/** 配置项树的一个分组（按注册键的路径首段分域：agent/llm/device/memory/policy/task/ui/features）。 */
public data class AdvancedGroup(
    val domain: String,
    val items: List<AdvancedItem>,
)

/** 一个配置段条目。 */
public data class AdvancedItem(
    /** 注册键路径（如 `device.serializer`）。 */
    val path: String,
    /** 是否有用户覆盖（当前值 ≠ 默认值）。 */
    val overridden: Boolean,
    /** 当前值（美化 JSON 文本）。 */
    val valueJson: String,
    /** 默认值（美化 JSON 文本）。 */
    val defaultJson: String,
)

/** 高级配置页状态。 */
public data class AdvancedUiState(
    /** 配置项树（分组按字典序）。 */
    val groups: List<AdvancedGroup> = emptyList(),
    /** 正在编辑的配置段（null=未打开编辑器）。 */
    val editing: AdvancedItem? = null,
    /** 编辑框文本。 */
    val editText: String = "",
    /** 校验错误（`config.invalid` 等的描述；null=无错误）。 */
    val editError: String? = null,
    /** 保存进行中。 */
    val saving: Boolean = false,
)
