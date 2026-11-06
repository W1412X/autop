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
import com.example.autop.flow.Condition
import com.example.autop.flow.DelayRange
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowSettings
import com.example.autop.flow.Node
import com.example.autop.flow.Precondition
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitFor
import com.example.autop.flow.WaitNode
import com.example.autop.flow.core.FlowToolRules
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

    /** 动作后自动写效果校验的工具。 */
    private val AUTO_EXPECT_TOOLS = setOf(TOOL_TAP, "input_text")

    /** 会切换页面的工具里的包名参数键。 */
    private const val ARG_PACKAGE = "package"

    /** 回放专用键，不能写进流程参数。 */
    private val INTERNAL_KEYS = setOf("_hit", "_locator")

    /** tap 的坐标键（由目标定位替代，不写进参数）。 */
    private val COORD_KEYS = setOf("x", "y")

    /**
     * 转换操作轨迹为流程。[settleMinMs]..[settleMaxMs] 为页面切换后的等待区间。
     * 结果可能没有任何步骤（轨迹里没有可流程化的操作），调用方需要检查。
     *
     * 自动写入（docs/design/flows-precondition-selfrepair.md P7）：
     * - tap / input_text 自动写 `expect.kind = SCREEN_CHANGED`，动作后校验效果；
     * - 打开应用后紧跟的节点补 `pre`（前台应用为该包名），页面没打开成功时在动作之前就拦下。
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
        // 打开应用后待补的前置条件；补给下一个真实节点后清空。
        var pendingApp: String? = null
        for (step in steps) {
            val tool = step.toolId.removePrefix(TOOL_PREFIX)
            if (tool in NON_REPLAYABLE || tool in FlowToolRules.FORBIDDEN) continue
            val id = "n${nodes.size + 1}"
            val base = if (tool == TOOL_TAP) tapNode(id, step.replayArgs) else genericNode(id, tool, step.replayArgs)
            val withExpect =
                if (tool in AUTO_EXPECT_TOOLS) {
                    base.copy(expect = Expect(kind = ExpectKind.SCREEN_CHANGED))
                } else {
                    base
                }
            val withPre =
                pendingApp?.let { app -> withExpect.copy(pre = Precondition(cond = Condition.App(value = app))) }
                    ?: withExpect
            pendingApp = null
            nodes += withPre
            if (tool in PAGE_CHANGING_TOOLS) {
                nodes +=
                    WaitNode(
                        id = "n${nodes.size + 1}",
                        waitFor = WaitFor.TIME,
                        ms = DelayRange(settleMinMs, settleMaxMs),
                    )
                if (tool == "open_app") {
                    pendingApp = (step.replayArgs[ARG_PACKAGE] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }
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
    ): ToolNode =
        ToolNode(
            id = id,
            tool = TOOL_TAP,
            args = HitTarget.publicArgs(args, dropCoords = true),
            target = HitTarget.targetOf(args),
        )

    private fun genericNode(
        id: String,
        tool: String,
        args: JsonObject,
    ): ToolNode = ToolNode(id = id, tool = tool, args = HitTarget.publicArgs(args, dropCoords = false))
}
