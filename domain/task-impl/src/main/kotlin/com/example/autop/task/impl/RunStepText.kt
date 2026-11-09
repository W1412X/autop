/**
 * 工具调用 → 当前操作的可读描述（运行控制条 / 通知栏 / 对话列表的 currentStep 展示）：
 * 动词按工具 id 映射，附参数里的关键目标（输入文本、应用名），过长截断。
 * 参数解析宽松防御：模型可能给非 JSON 或显式 null（LLM 流解析口径，`as?` 安全转换）。
 */
package com.example.autop.task.impl

import com.example.autop.core.common.AutopJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 目标文本附带时的最大长度（超出截断加省略号）。 */
private const val TARGET_MAX: Int = 12

/** 工具 id → 操作动词（无参数目标的工具直接用动词）。 */
private val VERBS: Map<String, String> =
    mapOf(
        "tool.tap" to "点击",
        "tool.input_text" to "输入",
        "tool.scroll" to "滚动",
        "tool.swipe" to "滑动",
        "tool.global_action" to "执行",
        "tool.dismiss_popup" to "关闭弹窗",
        "tool.restart_app" to "重启应用",
        "tool.open_app" to "打开应用",
        "tool.open_uri" to "打开链接",
        "tool.wait" to "等待",
        "tool.list_apps" to "查找应用",
        "tool.device_state" to "读取设备状态",
        "tool.get_device_state" to "读取设备状态",
        "tool.clipboard_get" to "读取剪贴板",
        "tool.clipboard_set" to "写入剪贴板",
        "tool.read_notifications" to "读取通知",
        "tool.screenshot" to "截屏",
        "tool.observe" to "观察界面",
        "tool.ask_user" to "向你提问",
        "tool.note" to "记录笔记",
        "tool.update_plan" to "更新计划",
        "tool.finish" to "完成任务",
        "tool.history_recall" to "回顾历史",
        "tool.run_flow" to "运行流程",
        "tool.save_as_flow" to "保存为流程",
        "tool.patch_flow_node" to "修改流程节点",
        "tool.create_flow" to "创建流程",
        "tool.edit_flow" to "编辑流程",
        "tool.list_tasks" to "查看任务",
        "tool.create_task" to "创建任务",
        "tool.update_task" to "更新任务",
        "tool.notify_user" to "通知你",
    )

/** `tool.global_action` 的动作 → 说法。 */
private val GLOBAL_ACTIONS: Map<String, String> =
    mapOf(
        "back" to "返回",
        "home" to "回到主页",
        "recents" to "打开最近任务",
        "notifications" to "下拉通知",
        "quick_settings" to "打开快捷设置",
        "lock" to "锁屏",
    )

/** 目标文本（展示就绪：已截断）；无目标返回 null。 */
internal fun targetOf(
    toolId: String,
    argsJson: String,
): String? {
    val args = argsOf(argsJson)
    val raw =
        when (canonToolId(toolId)) {
            "tool.input_text" -> args?.str("text")
            "tool.open_app" -> args?.str("name") ?: args?.str("package")
            "tool.list_apps" -> args?.str("query")
            "tool.global_action" -> args?.str("action")?.let { GLOBAL_ACTIONS[it] }
            else -> null
        }
    return raw?.trim()?.takeIf { it.isNotBlank() }?.abbreviate()
}

/** 工具 id 规范化：模型可见名（tap）与工具 id（tool.tap）都归一到工具 id。 */
internal fun canonToolId(toolId: String): String = if (toolId.startsWith("tool.")) toolId else "tool.$toolId"

/** 当前操作描述：动词 + 可选目标（「目标」过长截断）；未知工具回落去前缀的 id。 */
internal fun stepTextOf(
    toolId: String,
    argsJson: String,
): String {
    val id = canonToolId(toolId)
    val args = argsOf(argsJson)
    val verb =
        when (id) {
            "tool.tap" -> if (args?.bool("long") == true) "长按" else "点击"
            "tool.global_action" -> args?.str("action")?.let { GLOBAL_ACTIONS[it] } ?: "执行系统动作"
            else -> VERBS[id]
        } ?: id.removePrefix("tool.")
    val target =
        when (id) {
            "tool.input_text" -> args?.str("text")
            "tool.open_app" -> args?.str("name") ?: args?.str("package")
            "tool.list_apps" -> args?.str("query")
            else -> null
        }
    return if (target.isNullOrBlank()) verb else "$verb「${target.trim().abbreviate()}」"
}

/** 截断加省略号。 */
private fun String.abbreviate(): String = if (length > TARGET_MAX) take(TARGET_MAX) + "…" else this

/** 参数解析（宽松防御：坏 JSON 当无参数）。 */
private fun argsOf(argsJson: String): JsonObject? =
    runCatching { AutopJson.default.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject

/** 字符串参数；显式 null / 空串视为未提供。 */
private fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content?.takeIf { it.isNotEmpty() }

/** 布尔参数（"true"/"1"/"yes" 为真，宽松口径同 ToolArgs）。 */
private fun JsonObject.bool(name: String): Boolean? =
    str(name)?.let { it == "true" || it == "1" || it.equals("yes", ignoreCase = true) }
