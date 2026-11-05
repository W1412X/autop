/**
 * llm 观测解析器的输出协议（docs/design/observation-modes-coordinate-actions §4.3）：
 * 唯一工具 `report_elements`，参数为扁平元素列表，坐标顺序由模型原生格式决定。
 */
package com.example.autop.device.impl.vision.llm

import com.example.autop.agent.toolSpec
import com.example.autop.device.core.ScreenJsonWire

/** `report_elements` 工具名（强制调用时用于 `tool_choice`）。 */
internal const val REPORT_ELEMENTS: String = "report_elements"

/** 解析器工具 schema。 */
internal object LlmParserSpecs {
    fun spec(tags: List<String>) =
        toolSpec(REPORT_ELEMENTS, "device.llm_parser.tool") {
            array("elements", required = true) {
                string("tag", required = true, enum = tags)
                string("text")
                string("icon")
                string("desc")
                string("hint")
                string("state")
                array("act", itemEnum = ACT_WORDS)
                array("st", itemEnum = ST_WORDS)
                array("box", required = true, itemType = "integer", minItems = 4, maxItems = 4)
            }
        }

    private val ACT_WORDS: List<String> =
        listOf(ScreenJsonWire.ACT_CLICK, ScreenJsonWire.ACT_EDIT, ScreenJsonWire.ACT_SCROLL)

    private val ST_WORDS: List<String> =
        listOf(
            ScreenJsonWire.ST_CHECKED,
            ScreenJsonWire.ST_UNCHECKED,
            ScreenJsonWire.ST_SELECTED,
            ScreenJsonWire.ST_DISABLED,
            ScreenJsonWire.ST_UNCERTAIN,
        )
}
