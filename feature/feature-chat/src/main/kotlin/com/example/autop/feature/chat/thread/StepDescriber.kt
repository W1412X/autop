/**
 * 步骤描述（docs/tech/08 §4.2 StepRow：由 StepDescriber 根据工具与参数生成
 * "点击 “发送”" 这类描述，文案模板在 strings）。这里只输出标签与参数，
 * Screen 映射到 `R.string.step_*` 模板。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.core.common.AutopJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 工具 id → 描述标签。 */
public fun stepLabelOf(toolId: String): StepLabel = LABEL_BY_TOOL[toolId] ?: StepLabel.OTHER

/**
 * 描述参数（tap 的坐标、input_text 的文本等；`OTHER` 时为工具 id）。
 * 参数缺失或无法解析时返回 null，由 Screen 省略占位；多键工具取首个可用值
 * （open_app 的 name 回落 package），[JOIN_ARG_TOOLS] 的多键则逗号拼接。
 */
public fun stepArgOf(
    toolId: String,
    argsJson: String,
): String? =
    if (stepLabelOf(toolId) == StepLabel.OTHER) {
        toolId
    } else {
        parseArgs(argsJson)?.let { args -> argOf(toolId, args) }
    }

private fun argOf(
    toolId: String,
    args: JsonObject,
): String? {
    val values = ARG_KEYS_BY_TOOL[toolId].orEmpty().mapNotNull { key -> args.text(key) }
    return if (toolId in JOIN_ARG_TOOLS) {
        values.joinToString(",").ifBlank { null }
    } else {
        values.firstOrNull()
    }
}

private fun parseArgs(argsJson: String): JsonObject? =
    runCatching { AutopJson.default.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private val LABEL_BY_TOOL: Map<String, StepLabel> =
    mapOf(
        "tap" to StepLabel.TAP,
        "tap_node" to StepLabel.TAP,
        "input_text" to StepLabel.INPUT_TEXT,
        "scroll" to StepLabel.SCROLL,
        "scroll_node" to StepLabel.SCROLL,
        "swipe" to StepLabel.SWIPE,
        "global_action" to StepLabel.GLOBAL_ACTION,
        "open_app" to StepLabel.OPEN_APP,
        "open_uri" to StepLabel.OPEN_URI,
        "intent" to StepLabel.INTENT,
        "screenshot" to StepLabel.SCREENSHOT,
        "observe" to StepLabel.OBSERVE,
        "wait" to StepLabel.WAIT,
        "list_apps" to StepLabel.LIST_APPS,
        "get_device_state" to StepLabel.DEVICE_STATE,
        "clipboard_get" to StepLabel.CLIPBOARD_GET,
        "clipboard_set" to StepLabel.CLIPBOARD_SET,
        "list_tasks" to StepLabel.LIST_TASKS,
        "create_task" to StepLabel.CREATE_TASK,
        "update_task" to StepLabel.UPDATE_TASK,
    )

/** 描述参数取值键（按优先级；值全部缺失时返回 null）。 */
private val ARG_KEYS_BY_TOOL: Map<String, List<String>> =
    mapOf(
        "tap" to listOf("x", "y"),
        "tap_node" to listOf("id"),
        "observe" to listOf("mode"),
        "input_text" to listOf("text"),
        "scroll" to listOf("direction"),
        "scroll_node" to listOf("id", "direction"),
        "global_action" to listOf("action"),
        "open_app" to listOf("name", "package"),
        "open_uri" to listOf("uri"),
        "intent" to listOf("kind"),
        "list_apps" to listOf("query"),
        "clipboard_set" to listOf("text"),
        "create_task" to listOf("name", "goal"),
        "update_task" to listOf("task", "name", "goal"),
    )

/** 多键逗号拼接的工具（坐标类）。 */
private val JOIN_ARG_TOOLS: Set<String> = setOf("tap", "swipe")
