/**
 * 操作轨迹 → 流程定义（docs/design/flows-recording-recovery.md A3）：纯算法，不依赖 Android。
 *
 * 输入是一次运行中成功的可回放动作（[ReplayableAction]，含 tap 的命中回执 `_hit`）。
 * - 元工具（记忆、询问、通知、观测类等）与流程禁用工具不进入流程；
 * - tap 优先用命中节点的文字、描述、图标定位，没有时用坐标作为后备；坐标不展示给用户；
 * - 会切换页面的动作（打开应用、重启应用、打开链接）后插入一个等待节点。
 */
package com.example.autop.flow.core.author

import com.example.autop.agent.ReplayableAction
import com.example.autop.flow.DelayRange
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowSettings
import com.example.autop.flow.MatchMode
import com.example.autop.flow.Node
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.TargetFallback
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitFor
import com.example.autop.flow.WaitNode
import com.example.autop.flow.core.FlowToolRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 轨迹转换器。 */
public object TrajectoryConverter {
    /** 转换后的流程里 tap 工具名。 */
    public const val TOOL_TAP: String = "tap"

    /** 回放记录里的工具 id 前缀（`tool.tap`）。 */
    private const val TOOL_PREFIX = "tool."

    /** 不进入流程的工具：元操作、观测、记忆与交互类。 */
    private val NON_REPLAYABLE =
        setOf(
            "save_as_flow",
            "update_flow",
            "ask_user",
            "notify_user",
            "memory_write",
            "memory_read",
            "memory_search",
            "memory_list",
            "read_notifications",
            "get_device_state",
            "clipboard_get",
            "list_apps",
            "find_nodes",
            "read_node",
            "screenshot",
        )

    /** 会切换页面的工具，之后插入等待。 */
    private val PAGE_CHANGING_TOOLS = setOf("open_app", "restart_app", "open_uri")

    /** 回放专用键，不能写进流程参数。 */
    private val INTERNAL_KEYS = setOf("_hit", "_locator")

    /** tap 的坐标键（由目标定位替代，不写进参数）。 */
    private val COORD_KEYS = setOf("x", "y")

    /**
     * 转换操作轨迹为流程。[settleMinMs]..[settleMaxMs] 为页面切换后的等待区间。
     * 结果可能没有任何步骤（轨迹里没有可流程化的操作），调用方需要检查。
     */
    public fun convert(
        flowId: String,
        name: String,
        description: String,
        steps: List<ReplayableAction>,
        settleMinMs: Long,
        settleMaxMs: Long,
    ): FlowDefinition {
        val nodes = mutableListOf<Node>()
        for (step in steps) {
            val tool = step.toolId.removePrefix(TOOL_PREFIX)
            if (tool in NON_REPLAYABLE || tool in FlowToolRules.FORBIDDEN) continue
            val id = "n${nodes.size + 1}"
            nodes += if (tool == TOOL_TAP) tapNode(id, step.replayArgs) else genericNode(id, tool, step.replayArgs)
            if (tool in PAGE_CHANGING_TOOLS) {
                nodes +=
                    WaitNode(
                        id = "n${nodes.size + 1}",
                        waitFor = WaitFor.TIME,
                        ms = DelayRange(settleMinMs, settleMaxMs),
                    )
            }
        }
        return FlowDefinition(
            id = flowId,
            name = name,
            description = description,
            steps = nodes,
            settings = FlowSettings(),
        )
    }

    private fun tapNode(
        id: String,
        args: JsonObject,
    ): ToolNode {
        val extra = JsonObject(args.filterKeys { it !in INTERNAL_KEYS && it !in COORD_KEYS })
        return ToolNode(id = id, tool = TOOL_TAP, args = extra, target = targetOf(args))
    }

    private fun genericNode(
        id: String,
        tool: String,
        args: JsonObject,
    ): ToolNode = ToolNode(id = id, tool = tool, args = JsonObject(args.filterKeys { it !in INTERNAL_KEYS }))

    /** 由命中回执生成目标：文字 > 描述 > 图标；都没有时只保留坐标后备。 */
    private fun targetOf(args: JsonObject): Target {
        val hit = hitOf(args)
        val text = hit?.str("text")
        val desc = hit?.str("desc")
        val icon = hit?.str("icon")
        val point = point(args)
        val fallback = point?.let { TargetFallback(at = listOf(it.first, it.second)) }
        return when {
            text != null -> Target(by = TargetBy.TEXT, value = text, match = MatchMode.EQUALS, fallback = fallback)
            desc != null -> Target(by = TargetBy.DESC, value = desc, match = MatchMode.EQUALS, fallback = fallback)
            icon != null -> Target(by = TargetBy.ICON, value = icon, match = MatchMode.EQUALS, fallback = fallback)
            else -> Target(by = TargetBy.TEXT, value = null, fallback = fallback)
        }
    }

    private fun hitOf(args: JsonObject): JsonObject? {
        val raw: JsonElement = args["_hit"] ?: return null
        val obj =
            when (raw) {
                is JsonObject -> raw
                is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
                else -> null
            }
        return (obj?.get("hit") as? JsonObject) ?: obj
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun point(args: JsonObject): Pair<Int, Int>? {
        val x = (args["x"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val y = (args["y"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        return x to y
    }
}
