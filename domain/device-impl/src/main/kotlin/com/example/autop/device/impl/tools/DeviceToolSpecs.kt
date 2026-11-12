/**
 * 设备类工具的参数结构（docs/tech/05 §5 的参数表）：稳定 id + `toolSpec` DSL。
 * 参数说明取模板 `meta["param.<名>"]`，由 `ToolSchemaBuilder` 组装（测试覆盖全部工具）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.device.core.NavMode
import com.example.autop.device.core.SettingsPage

/** `intent` 的 kind 枚举（与 [com.example.autop.device.core.kind] 一致）。 */
private val INTENT_KINDS: List<String> =
    listOf("alarm", "timer", "calendar", "dial", "sms_draft", "share", "navigate", "settings", "uri")

/** 设备类工具的 spec。 */
public object DeviceToolSpecs {
    /** `tool.observe`：静态形态（全模式枚举）；运行期由 [observeFor] 按生效模式收窄。 */
    public val observe: ToolSpec = observeFor(listOf("a11y", "visual"), "")

    /** `tool.observe` 的运行期形态：[modeIds] 为生效模式枚举，[modeList] 为说明中的优先级列表。 */
    public fun observeFor(
        modeIds: List<String>,
        modeList: String,
    ): ToolSpec =
        toolSpec(
            name = "observe",
            descriptionPromptId = "tool.observe",
            descriptionVars = mapOf("modeList" to modeList),
        ) {
            string("mode", enum = modeIds)
        }

    /** `tool.screenshot`（截图不含编号框与网格）。 */
    public val screenshot: ToolSpec =
        toolSpec(name = "screenshot", descriptionPromptId = "tool.screenshot") {
            obj("region") {
                integer("x1", required = true)
                integer("y1", required = true)
                integer("x2", required = true)
                integer("y2", required = true)
            }
            obj("mark_point") {
                integer("x", required = true)
                integer("y", required = true)
            }
            string("reason", required = true)
        }

    /** `tool.tap`：坐标 0–1000 必填；时长与抖动由工具内部处理。 */
    public val tap: ToolSpec =
        toolSpec(name = "tap", descriptionPromptId = "tool.tap") {
            integer("x", required = true, min = 0, max = 1000)
            integer("y", required = true, min = 0, max = 1000)
            boolean("long")
            integer("wait_ms", min = 0)
        }

    /** `tool.tap_node`：对 a11y 观测里带 id 的节点执行无障碍点击动作。 */
    public val tapNode: ToolSpec =
        toolSpec(name = "tap_node", descriptionPromptId = "tool.tap_node") {
            integer("id", required = true, min = 1)
            boolean("long")
            integer("wait_ms", min = 0)
        }

    /** `tool.scroll_node`：对可滚动节点执行无障碍滚动动作。 */
    public val scrollNode: ToolSpec =
        toolSpec(name = "scroll_node", descriptionPromptId = "tool.scroll_node") {
            integer("id", required = true, min = 1)
            string("direction", required = true, enum = listOf("up", "down", "left", "right"))
            integer("steps", min = 1)
            integer("wait_ms", min = 0)
        }

    /** `tool.input_text`：写入焦点输入框，可选先点击输入框坐标。 */
    public val inputText: ToolSpec =
        toolSpec(name = "input_text", descriptionPromptId = "tool.input_text") {
            string("text", required = true)
            integer("x", min = 0, max = 1000)
            integer("y", min = 0, max = 1000)
            string("mode", enum = listOf("replace", "append"))
            boolean("submit")
            integer("wait_ms", min = 0)
        }

    /** `tool.scroll`：单步人手滑动，`steps` 连续多步；`x,y` 为滚动区域内一点。 */
    public val scroll: ToolSpec =
        toolSpec(name = "scroll", descriptionPromptId = "tool.scroll") {
            string("direction", required = true, enum = listOf("up", "down", "left", "right"))
            integer("steps", min = 1)
            // 已废弃且被忽略：只为让旧流程里保存的 amount 仍能通过参数校验（ADR-0015）。
            string("amount", enum = listOf("page", "half"))
            integer("x", min = 0, max = 1000)
            integer("y", min = 0, max = 1000)
            integer("wait_ms", min = 0)
        }

    /** `tool.swipe`。 */
    public val swipe: ToolSpec =
        toolSpec(name = "swipe", descriptionPromptId = "tool.swipe") {
            integer("x1", required = true, min = 0, max = 1000)
            integer("y1", required = true, min = 0, max = 1000)
            integer("x2", required = true, min = 0, max = 1000)
            integer("y2", required = true, min = 0, max = 1000)
            integer("duration_ms", min = 1)
            integer("wait_ms", min = 0)
        }

    /** `tool.global_action`。 */
    public val globalAction: ToolSpec =
        toolSpec(name = "global_action", descriptionPromptId = "tool.global_action") {
            string(
                "action",
                required = true,
                enum = listOf("back", "home", "recents", "notifications", "quick_settings", "lock"),
            )
            integer("wait_ms", min = 0)
        }

    /** `tool.restart_app`：强制结束并重新打开应用；package 缺省时用当前前台应用。 */
    public val restartApp: ToolSpec =
        toolSpec(name = "restart_app", descriptionPromptId = "tool.restart_app") {
            string("package")
            integer("wait_ms", min = 0)
        }

    /** `tool.open_app`：`package` 与 `name` 二选一。 */
    public val openApp: ToolSpec =
        toolSpec(name = "open_app", descriptionPromptId = "tool.open_app") {
            string("package")
            string("name")
            integer("wait_ms", min = 0)
            oneOfRequired(listOf("package"), listOf("name"))
        }

    /** `tool.intent` 的静态形态（导航应用枚举为空）；运行期由 [intentFor] 按规则表收窄。 */
    public val intent: ToolSpec = intentFor(emptyList(), "")

    /**
     * `tool.intent` 的运行期形态：[mapIds] 为导航规则表里的应用 id（`map` 的枚举），
     * [mapList] 为说明中的可选项列表（如 `amap=高德地图、…`）。
     */
    public fun intentFor(
        mapIds: List<String>,
        mapList: String,
    ): ToolSpec =
        toolSpec(
            name = "intent",
            descriptionPromptId = "tool.intent",
            descriptionVars = mapOf("mapList" to mapList),
        ) {
            string("kind", required = true, enum = INTENT_KINDS)
            integer("hour")
            integer("minute")
            array("days", itemType = "integer")
            integer("seconds")
            string("message")
            string("title")
            string("begin")
            string("end")
            boolean("all_day")
            string("location")
            string("description")
            string("number")
            string("text")
            string("app")
            string("destination")
            number("lat", min = -90.0, max = 90.0)
            number("lng", min = -180.0, max = 180.0)
            string("mode", enum = NavMode.entries.map { it.id })
            string("map", enum = mapIds.takeIf { it.isNotEmpty() })
            string("page", enum = SettingsPage.entries.map { it.id })
            string("uri")
            integer("wait_ms", min = 0)
        }

    /** `tool.wait`：`ms` 或 `until` 条件二选一（同传时按 `ms` 固定等待）。 */
    public val wait: ToolSpec =
        toolSpec(name = "wait", descriptionPromptId = "tool.wait") {
            integer("ms", min = 0)
            obj("until") {
                string("text_appears")
                string("text_disappears")
                string("package_is")
                integer("timeout_ms")
            }
            integer("timeout_ms")
            oneOfRequired(listOf("ms"), listOf("until"))
        }

    /** `tool.list_apps`。 */
    public val listApps: ToolSpec =
        toolSpec(name = "list_apps", descriptionPromptId = "tool.list_apps") {
            string("query")
            integer("limit", min = 1)
        }

    /** `tool.clipboard_get`。 */
    public val clipboardGet: ToolSpec =
        toolSpec(name = "clipboard_get", descriptionPromptId = "tool.clipboard_get") {}

    /** `tool.clipboard_set`。 */
    public val clipboardSet: ToolSpec =
        toolSpec(name = "clipboard_set", descriptionPromptId = "tool.clipboard_set") {
            string("text", required = true)
        }

    /** `tool.read_notifications`（WP9）：读取近期通知（05 §5、§9）。 */
    public val readNotifications: ToolSpec =
        toolSpec(name = "read_notifications", descriptionPromptId = "tool.read_notifications") {
            string("package")
            integer("since_minutes", min = 0, max = 43200)
            integer("limit", min = 1, max = 50)
        }

    /** 全部 spec（测试逐个校验 meta 完整性）。 */
    public val ALL: List<ToolSpec> =
        listOf(
            observe,
            screenshot,
            tap,
            tapNode,
            inputText,
            scroll,
            scrollNode,
            swipe,
            globalAction,
            restartApp,
            openApp,
            intent,
            wait,
            listApps,
            clipboardGet,
            clipboardSet,
            readNotifications,
        )
}
