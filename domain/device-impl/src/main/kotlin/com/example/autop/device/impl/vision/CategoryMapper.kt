/**
 * 检测类别 → NodeSnapshot 映射（vp8 §8.2）：className 用 `visual.*` 前缀标注推断值，与真类名
 * 区分；v2 11 类（ONNX metadata `names`）与 v1 键（单类兜底档 SubtypeRefiner 细化结果）同表。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.NodeFlags

/** 类别映射器。 */
internal object CategoryMapper {
    /** AccessibilityNodeInfo.ACTION_CLICK。 */
    const val ACTION_CLICK: Int = 16

    /** 状态分类结果写入 stateDesc 的文案（vp8 §6.3，复用新方案 2.3 输出）。 */
    const val STATE_ON: String = "已开启"
    const val STATE_OFF: String = "已关闭"

    private const val ON: Int = NodeFlags.ENABLED or NodeFlags.VISIBLE
    private const val INTERACTIVE: Int =
        NodeFlags.CLICKABLE or NodeFlags.EDITABLE or NodeFlags.CHECKABLE or NodeFlags.SCROLLABLE

    private data class Meta(
        val className: String,
        val flags: Int,
    )

    private val META: Map<String, Meta> =
        mapOf(
            // ---- v1（单类兜底档）----
            VisualCategory.BUTTON to Meta("visual.Button", NodeFlags.CLICKABLE or ON),
            VisualCategory.ICON_CLICKABLE to Meta("visual.ImageButton", NodeFlags.CLICKABLE or ON),
            VisualCategory.INPUT_FIELD to Meta("visual.EditText", NodeFlags.EDITABLE or NodeFlags.FOCUSABLE or ON),
            VisualCategory.SWITCH to Meta("visual.Switch", NodeFlags.CHECKABLE or NodeFlags.CLICKABLE or ON),
            VisualCategory.CHECKBOX to Meta("visual.CheckBox", NodeFlags.CHECKABLE or NodeFlags.CLICKABLE or ON),
            VisualCategory.RADIO to Meta("visual.RadioButton", NodeFlags.CHECKABLE or NodeFlags.CLICKABLE or ON),
            VisualCategory.SEEKBAR to Meta("visual.SeekBar", NodeFlags.CLICKABLE or ON),
            VisualCategory.TEXT_LABEL to Meta("visual.TextView", ON),
            VisualCategory.TAB to Meta("visual.Tab", NodeFlags.CLICKABLE or ON),
            VisualCategory.TAB_GROUP to Meta("visual.TabWidget", ON),
            VisualCategory.LIST_ITEM to Meta("visual.Item", NodeFlags.CLICKABLE or ON),
            VisualCategory.CONTAINER to Meta("visual.ViewGroup", NodeFlags.VISIBLE),
            // ---- v2（vp8 11 类，vp8 §8.2）----
            VisualCategory.TEXT to Meta("visual.TextView", ON),
            VisualCategory.ICON to Meta("visual.ImageButton", NodeFlags.CLICKABLE or ON),
            VisualCategory.IMAGE to Meta("visual.ImageView", ON),
            VisualCategory.ITEM to Meta("visual.Item", NodeFlags.CLICKABLE or ON),
            VisualCategory.INPUT to Meta("visual.EditText", NodeFlags.EDITABLE or NodeFlags.FOCUSABLE or ON),
            VisualCategory.SLIDER to Meta("visual.SeekBar", NodeFlags.CLICKABLE or NodeFlags.SCROLLABLE or ON),
            VisualCategory.PROGRESS to Meta("visual.ProgressBar", ON),
        )

    /** 合成类名（未知类别按容器兜底）。 */
    fun classNameOf(category: String): String = metaOf(category).className

    /** NodeFlags 位（未知类别按容器兜底）；[checked] 为 true 时加 CHECKED（vp8 §8.2）。 */
    fun flagsOf(
        category: String,
        checked: Boolean? = null,
    ): Int = if (checked == true) metaOf(category).flags or NodeFlags.CHECKED else metaOf(category).flags

    /** 状态分类三态 → stateDesc（未知不写，vp8 §6.3）。 */
    fun stateDescOf(checked: Boolean?): String? =
        when (checked) {
            true -> STATE_ON
            false -> STATE_OFF
            null -> null
        }

    /** 可交互类给 ACTION_CLICK，否则空。 */
    fun actionsOf(category: String): List<Int> =
        if (flagsOf(category) and INTERACTIVE != 0) listOf(ACTION_CLICK) else emptyList()

    private fun metaOf(category: String): Meta = META[category] ?: META.getValue(VisualCategory.CONTAINER)
}
