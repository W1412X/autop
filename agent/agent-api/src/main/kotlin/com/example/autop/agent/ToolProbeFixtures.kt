/**
 * 工具自测默认参数表（N198）：键为 spec.name，供 [ToolProbeService] 面板回填与探测回退。
 *
 * 参数名与 toolSpec DSL 一一对应（DeviceToolSpecs / MemoryToolSpecs / 控制工具 / FlowTools spec）；
 * 新增工具须在此补条目，缺失时回退空对象（参数不合法会得到错误结果，也是有效诊断输出）。
 * 放在 agent-api 是为了给各 impl 模块的测试做覆盖守护（ToolProbeFixtures.names ⊇ 本模块全部 spec）。
 */
package com.example.autop.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public object ToolProbeFixtures {
    private val fixtures: Map<String, JsonObject> =
        mapOf(
            "observe" to jsonObjectOf("mode" to "a11y", "full" to true),
            "screenshot" to jsonObjectOf("reason" to "工具自测（N198）"),
            "tap" to jsonObjectOf("x" to 500, "y" to 500),
            "input_text" to jsonObjectOf("text" to "autop 自测"),
            "scroll" to jsonObjectOf("direction" to "down"),
            "swipe" to jsonObjectOf("x1" to 500, "y1" to 700, "x2" to 500, "y2" to 300, "duration_ms" to 300),
            "global_action" to jsonObjectOf("action" to "notifications"),
            "open_app" to jsonObjectOf("package" to "com.android.settings"),
            "open_uri" to jsonObjectOf("uri" to "https://example.com"),
            "wait" to jsonObjectOf("ms" to 200),
            "list_apps" to jsonObjectOf("query" to "设置"),
            "get_device_state" to jsonObjectOf(),
            "clipboard_get" to jsonObjectOf(),
            "clipboard_set" to jsonObjectOf("text" to "autop-probe"),
            "read_notifications" to jsonObjectOf(),
            "dismiss_popup" to jsonObjectOf(),
            "restart_app" to jsonObjectOf("package" to "com.android.settings"),
            "memory_list" to jsonObjectOf("path" to "/"),
            "memory_read" to jsonObjectOf("path" to "/"),
            "memory_search" to jsonObjectOf("query" to "自测"),
            "memory_write" to
                jsonObjectOf(
                    "path" to "/probe/n198",
                    "mode" to "CREATE",
                    "content" to "工具自测条目（N198）",
                    "reason" to "工具自测",
                ),
            "update_plan" to """{"items":[{"step":"工具自测","status":"pending"}]}""".let(::jsonElementOf),
            "finish" to jsonObjectOf("status" to "success", "summary" to "工具自测"),
            "ask_user" to jsonObjectOf("question" to "工具自测？"),
            "note" to jsonObjectOf("text" to "工具自测笔记（N198）"),
            "history_recall" to jsonObjectOf("query" to "自测"),
            "notify_user" to jsonObjectOf("title" to "工具自测", "text" to "工具自测通知（N203）"),
            "run_flow" to jsonObjectOf("flow" to "自测流程"),
            "list_flows" to jsonObjectOf(),
            "save_as_flow" to jsonObjectOf("name" to "工具自测流程", "description" to "工具自测草稿（N198）"),
            "update_flow" to jsonObjectOf("flow" to "自测流程", "reason" to "工具自测"),
            "patch_flow_node" to jsonObjectOf("flow" to "自测流程", "node_id" to "n1", "reason" to "工具自测"),
        )

    /** 有默认测试参数的工具名（测试守护：注册的工具须全部落在这里）。 */
    public val names: Set<String> = fixtures.keys

    /** 工具的默认测试参数；无条目回退空对象。 */
    public fun fixtureFor(toolName: String): JsonObject = fixtures[toolName] ?: JsonObject(emptyMap())
}

/** 简便构造：字符串/数字值的 JSON 对象。 */
private fun jsonObjectOf(vararg pairs: Pair<String, Any>): JsonObject =
    JsonObject(
        pairs.associate { (key, value) ->
            key to
                when (value) {
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
        },
    )

/** 文本直通 JSON 元素（结构复杂的 fixture 直接写 JSON 字符串）。 */
private fun jsonElementOf(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
