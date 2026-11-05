/**
 * 设备类工具的参数结构（docs/tech/05 §5 的参数表）：稳定 id + `toolSpec` DSL。
 * 参数说明取模板 `meta["param.<名>"]`，由 `ToolSchemaBuilder` 组装（测试覆盖全部工具）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec

/** 15 个设备工具的 spec（含 `read_notifications`）。 */
public object DeviceToolSpecs {
    /** `tool.observe`：静态形态（全模式枚举）；运行期由 [observeFor] 按生效模式收窄。 */
    public val observe: ToolSpec = observeFor(listOf("a11y", "visual", "llm"), "")

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
            string("mode", required = true, enum = modeIds)
            boolean("full")
            obj("region") {
                integer("x1", required = true)
                integer("y1", required = true)
                integer("x2", required = true)
                integer("y2", required = true)
            }
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
            integer("x", required = true)
            integer("y", required = true)
            boolean("long")
            integer("wait_ms")
        }

    /** `tool.input_text`：写入焦点输入框，可选先点击输入框坐标。 */
    public val inputText: ToolSpec =
        toolSpec(name = "input_text", descriptionPromptId = "tool.input_text") {
            string("text", required = true)
            integer("x")
            integer("y")
            string("mode", enum = listOf("replace", "append"))
            boolean("submit")
            integer("wait_ms")
        }

    /** `tool.scroll`：只用手势；`x,y` 为滚动区域内一点。 */
    public val scroll: ToolSpec =
        toolSpec(name = "scroll", descriptionPromptId = "tool.scroll") {
            string("direction", required = true, enum = listOf("up", "down", "left", "right"))
            string("amount", enum = listOf("page", "half"))
            integer("x")
            integer("y")
        }

    /** `tool.swipe`。 */
    public val swipe: ToolSpec =
        toolSpec(name = "swipe", descriptionPromptId = "tool.swipe") {
            integer("x1", required = true)
            integer("y1", required = true)
            integer("x2", required = true)
            integer("y2", required = true)
            integer("duration_ms")
        }

    /** `tool.global_action`。 */
    public val globalAction: ToolSpec =
        toolSpec(name = "global_action", descriptionPromptId = "tool.global_action") {
            string(
                "action",
                required = true,
                enum = listOf("back", "home", "recents", "notifications", "quick_settings", "lock"),
            )
        }

    /** `tool.open_app`：`package` 与 `name` 二选一。 */
    public val openApp: ToolSpec =
        toolSpec(name = "open_app", descriptionPromptId = "tool.open_app") {
            string("package")
            string("name")
            oneOfRequired(listOf("package"), listOf("name"))
        }

    /** `tool.open_uri`。 */
    public val openUri: ToolSpec =
        toolSpec(name = "open_uri", descriptionPromptId = "tool.open_uri") {
            string("uri", required = true)
        }

    /** `tool.wait`：`ms` 或 `until` 条件二选一（同传时按 `ms` 固定等待）。 */
    public val wait: ToolSpec =
        toolSpec(name = "wait", descriptionPromptId = "tool.wait") {
            integer("ms")
            obj("until") {
                string("text_appears")
                string("text_disappears")
                string("package_is")
            }
            integer("timeout_ms")
            oneOfRequired(listOf("ms"), listOf("until"))
        }

    /** `tool.list_apps`。 */
    public val listApps: ToolSpec =
        toolSpec(name = "list_apps", descriptionPromptId = "tool.list_apps") {
            string("query")
            integer("limit")
        }

    /** `tool.device_state`（name 为 `get_device_state`）。 */
    public val deviceState: ToolSpec =
        toolSpec(name = "get_device_state", descriptionPromptId = "tool.get_device_state") {}

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
            integer("since_minutes")
            integer("limit")
        }

    /** 全部 spec（测试逐个校验 meta 完整性）。 */
    public val ALL: List<ToolSpec> =
        listOf(
            observe,
            screenshot,
            tap,
            inputText,
            scroll,
            swipe,
            globalAction,
            openApp,
            openUri,
            wait,
            listApps,
            deviceState,
            clipboardGet,
            clipboardSet,
            readNotifications,
        )
}
