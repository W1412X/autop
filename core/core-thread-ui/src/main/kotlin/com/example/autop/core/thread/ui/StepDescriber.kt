/**
 * 步骤描述（docs/tech/08 §4.2 StepRow：由 StepDescriber 根据工具与参数生成
 * "点击 “发送”" 这类描述，文案模板在 strings）。这里只输出标签与参数，
 * Screen 映射到 `R.string.step_*` 模板。
 */
package com.example.autop.core.thread.ui

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

/**
 * run_shell 的完整命令：折叠行只显示"执行命令"，命令原文在展开详情里查看。
 * 命令可能很长且多行，不参与折叠行描述。
 */
public fun stepShellCommandOf(argsJson: String): String? = parseArgs(argsJson)?.text("command")

private fun argOf(
    toolId: String,
    args: JsonObject,
): String? {
    val values =
        ARG_KEYS_BY_TOOL[toolId].orEmpty().mapNotNull { key ->
            args.text(key)?.oneLine()?.takeIf { it.isNotEmpty() }
        }
    return if (toolId in JOIN_ARG_TOOLS) {
        values.joinToString(",").ifBlank { null }
    } else {
        values.firstOrNull()
    }
}

/**
 * 描述参数单行化：换行与连续空白折叠为空格。
 * 折叠行只占一行（多行文本参数不会把行撑高），完整原文在展开详情里看。
 */
private fun String.oneLine(): String = replace(WHITESPACE, " ").trim()

private val WHITESPACE: Regex = Regex("\\s+")

private fun parseArgs(argsJson: String): JsonObject? =
    runCatching { AutopJson.default.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private val LABEL_BY_TOOL: Map<String, StepLabel> =
    mapOf(
        "tap" to StepLabel.TAP,
        "tap_node" to StepLabel.TAP_NODE,
        "input_text" to StepLabel.INPUT_TEXT,
        "scroll" to StepLabel.SCROLL,
        "scroll_node" to StepLabel.SCROLL_NODE,
        "swipe" to StepLabel.SWIPE,
        "global_action" to StepLabel.GLOBAL_ACTION,
        "open_app" to StepLabel.OPEN_APP,
        "restart_app" to StepLabel.RESTART_APP,
        "intent" to StepLabel.INTENT,
        "screenshot" to StepLabel.SCREENSHOT,
        "observe" to StepLabel.OBSERVE,
        "wait" to StepLabel.WAIT,
        "list_apps" to StepLabel.LIST_APPS,
        "list_tasks" to StepLabel.LIST_TASKS,
        "create_task" to StepLabel.CREATE_TASK,
        "update_task" to StepLabel.UPDATE_TASK,
        "notify_user" to StepLabel.NOTIFY_USER,
        "read_notifications" to StepLabel.READ_NOTIFICATIONS,
        "clipboard_get" to StepLabel.CLIPBOARD_GET,
        "clipboard_set" to StepLabel.CLIPBOARD_SET,
        "run_shell" to StepLabel.RUN_SHELL,
        "get_device_state" to StepLabel.DEVICE_STATE,
        "ask_user" to StepLabel.ASK_USER,
        "update_plan" to StepLabel.UPDATE_PLAN,
        "finish" to StepLabel.FINISH,
        "note" to StepLabel.NOTE,
        "history_recall" to StepLabel.HISTORY_RECALL,
        "run_flow" to StepLabel.RUN_FLOW,
        "list_flows" to StepLabel.LIST_FLOWS,
        "create_flow" to StepLabel.CREATE_FLOW,
        "edit_flow" to StepLabel.EDIT_FLOW,
        "update_flow" to StepLabel.UPDATE_FLOW,
        "save_as_flow" to StepLabel.SAVE_AS_FLOW,
        "patch_flow_node" to StepLabel.PATCH_FLOW_NODE,
        "memory_list" to StepLabel.MEMORY_LIST,
        "memory_read" to StepLabel.MEMORY_READ,
        "memory_search" to StepLabel.MEMORY_SEARCH,
        "memory_write" to StepLabel.MEMORY_WRITE,
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
        "restart_app" to listOf("package"),
        "intent" to listOf("kind"),
        "screenshot" to listOf("reason"),
        "list_apps" to listOf("query"),
        "read_notifications" to listOf("package"),
        "clipboard_set" to listOf("text"),
        "ask_user" to listOf("question"),
        "finish" to listOf("summary"),
        "note" to listOf("text"),
        "history_recall" to listOf("query"),
        "notify_user" to listOf("title", "text"),
        "create_task" to listOf("name", "goal"),
        "update_task" to listOf("task", "name", "goal"),
        "run_flow" to listOf("flow"),
        "create_flow" to listOf("name"),
        "edit_flow" to listOf("flow"),
        "update_flow" to listOf("flow"),
        "save_as_flow" to listOf("name"),
        "patch_flow_node" to listOf("flow", "node_id"),
        "memory_list" to listOf("path"),
        "memory_read" to listOf("path"),
        "memory_search" to listOf("query"),
        "memory_write" to listOf("path"),
    )

/** 多键逗号拼接的工具（坐标类）。 */
private val JOIN_ARG_TOOLS: Set<String> = setOf("tap", "swipe")
