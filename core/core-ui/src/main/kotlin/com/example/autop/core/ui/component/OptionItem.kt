/**
 * [OptionSheet] 的单个选项。
 */
package com.example.autop.core.ui.component

/** 选项：稳定 [id] 用于选中判定，[label] 展示，[description] 可选说明。 */
public data class OptionItem(
    val id: String,
    val label: String,
    val description: String? = null,
)
