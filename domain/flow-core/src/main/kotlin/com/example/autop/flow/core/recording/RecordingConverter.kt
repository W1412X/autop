/**
 * 录制操作 → 流程草稿（docs/design/flows-recording-recovery.md V4、V6）：纯算法。
 *
 * - 开始录制时的应用先用 `open_app` 打开，并作为锚点；
 * - 点击优先用文字、描述定位，没有时用节点中心的屏幕比例坐标（0–1000）后备，坐标不展示给用户；
 * - 连续的文字输入合并为一步，记录在 [Result.typedNodes]，用于询问是否设为参数；
 * - 点击后出现窗口切换时，给该点击设置“屏幕应变化”的期望，并插入等待；
 * - 密码框的输入直接丢弃。
 */
package com.example.autop.flow.core.recording

import com.example.autop.device.UserAction
import com.example.autop.device.UserActionType
import com.example.autop.flow.Anchor
import com.example.autop.flow.DelayRange
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowParam
import com.example.autop.flow.FlowSettings
import com.example.autop.flow.MatchMode
import com.example.autop.flow.Node
import com.example.autop.flow.ParamType
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.TargetFallback
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitFor
import com.example.autop.flow.WaitNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 屏幕像素尺寸（用于比例坐标后备）。 */
public data class ScreenSize(
    val width: Int,
    val height: Int,
)

/** 录制转换选项。 */
public data class RecordingOptions(
    /** 页面切换后的等待区间（毫秒）。 */
    val settleMinMs: Long,
    val settleMaxMs: Long,
    /** 节点数量上限。 */
    val maxSteps: Int,
)

/** 录制转换结果。 */
public data class RecordingDraft(
    val definition: FlowDefinition,
    /** 文字输入节点的 id → 输入的文字（用户可把它们设为参数）。 */
    val typedNodes: Map<String, String>,
)

/** 录制操作转换器。 */
public object RecordingConverter {
    private const val TOOL_TAP = "tap"
    private const val TOOL_INPUT = "input_text"
    private const val TOOL_SCROLL = "scroll"
    private const val TOOL_OPEN_APP = "open_app"
    private const val ARG_TEXT = "text"
    private const val ARG_DIRECTION = "direction"
    private const val ARG_PACKAGE = "package"
    private const val ARG_LONG = "long"
    private const val PARAM_TEMPLATE = "{{%s}}"
    private const val PERMILLE = 1000

    /**
     * 把操作序列转换成流程草稿。[screenWidth]/[screenHeight] 为像素尺寸，用于计算比例坐标后备；
     * [maxSteps] 限制节点数量（超出部分丢弃）。
     */
    public fun convert(
        flowId: String,
        name: String,
        actions: List<UserAction>,
        screen: ScreenSize,
        options: RecordingOptions,
    ): RecordingDraft {
        val builder =
            Builder(screen.width, screen.height, DelayRange(options.settleMinMs, options.settleMaxMs), options.maxSteps)
        builder.run(actions)
        val definition =
            FlowDefinition(id = flowId, name = name, steps = builder.nodes, settings = FlowSettings())
        return RecordingDraft(definition, builder.typed)
    }

    /**
     * 把文字输入节点设为参数：节点文字改为 `{{参数名}}`，原文字作为参数默认值。
     * [paramNames] 为 节点 id → 参数名；未出现的节点保持不变。
     */
    public fun promoteParams(
        definition: FlowDefinition,
        paramNames: Map<String, String>,
    ): FlowDefinition {
        if (paramNames.isEmpty()) return definition
        val params = mutableListOf<FlowParam>()
        val steps =
            definition.steps.map { node ->
                val paramName = paramNames[node.id]
                if (node is ToolNode && paramName != null) {
                    val original = (node.args[ARG_TEXT] as? JsonPrimitive)?.content
                    params +=
                        FlowParam(
                            name = paramName,
                            type = ParamType.STRING,
                            default = original?.let { JsonPrimitive(it) },
                        )
                    val args = node.args + (ARG_TEXT to JsonPrimitive(PARAM_TEMPLATE.format(paramName)))
                    node.copy(args = JsonObject(args))
                } else {
                    node
                }
            }
        return definition.copy(steps = steps, params = definition.params + params)
    }

    /** 转换过程的可变状态。 */
    private class Builder(
        val screenWidth: Int,
        val screenHeight: Int,
        val settle: DelayRange,
        val maxSteps: Int,
    ) {
        val nodes = mutableListOf<Node>()
        val typed = linkedMapOf<String, String>()
        private var previousPackage: String? = null

        fun run(actions: List<UserAction>) {
            val startPackage = actions.firstOrNull { it.packageName != null }?.packageName
            if (startPackage != null) {
                nodes +=
                    ToolNode(
                        id = nextId(),
                        tool = TOOL_OPEN_APP,
                        args = obj(ARG_PACKAGE to startPackage),
                        anchor = Anchor(app = startPackage),
                    )
                nodes += settleWait()
                previousPackage = startPackage
            }
            var index = 0
            while (index < actions.size && nodes.size < maxSteps) {
                val action = actions[index]
                index++
                index = handle(action, actions, index)
            }
        }

        /** 处理一个操作，返回下一个待处理的下标。 */
        private fun handle(
            action: UserAction,
            actions: List<UserAction>,
            next: Int,
        ): Int =
            when (action.type) {
                UserActionType.CLICK, UserActionType.LONG_CLICK -> click(action, actions, next)
                UserActionType.TEXT_CHANGED -> text(action, actions, next)
                UserActionType.SCROLL_FORWARD -> scrollStep("down", action, actions, next)
                UserActionType.SCROLL_BACKWARD -> scrollStep("up", action, actions, next)
                UserActionType.WINDOW_CHANGED -> windowChanged(action, actions, next)
                UserActionType.WAIT_ELEMENT -> waitElement(action, next)
            }

        private fun click(
            action: UserAction,
            actions: List<UserAction>,
            next: Int,
        ): Int {
            val target = targetOf(action, asInput = false) ?: return next
            val args = if (action.type == UserActionType.LONG_CLICK) obj(ARG_LONG to true) else JsonObject(emptyMap())
            val node = ToolNode(id = nextId(), tool = TOOL_TAP, args = args, target = target, anchor = anchorOf(action))
            nodes += node
            if (actions.getOrNull(next)?.type != UserActionType.WINDOW_CHANGED) return next
            nodes[nodes.lastIndex] = node.copy(expect = Expect(kind = ExpectKind.SCREEN_CHANGED))
            nodes += settleWait()
            return skipWindowEvents(actions, next)
        }

        private fun text(
            action: UserAction,
            actions: List<UserAction>,
            next: Int,
        ): Int {
            if (action.isPassword) return next
            var last = action
            var cursor = next
            while (actions
                    .getOrNull(
                        cursor,
                    )?.let { it.type == UserActionType.TEXT_CHANGED && it.bounds == action.bounds } ==
                true
            ) {
                last = actions[cursor]
                cursor++
            }
            val value = last.text?.takeIf { it.isNotEmpty() } ?: return cursor
            val target = targetOf(action, asInput = true) ?: return cursor
            val node =
                ToolNode(
                    id = nextId(),
                    tool = TOOL_INPUT,
                    args = obj(ARG_TEXT to value),
                    target = target,
                    anchor = anchorOf(action),
                )
            nodes += node
            typed[node.id] = value
            return cursor
        }

        /** 等待元素出现；目标无法定位时跳过。 */
        private fun waitElement(
            action: UserAction,
            next: Int,
        ): Int {
            val target = targetOf(action, asInput = false) ?: return next
            nodes += WaitNode(id = nextId(), waitFor = WaitFor.ELEMENT, target = target)
            return next
        }

        /** 连续同向滚动合并为一步，返回下一个待处理的下标。 */
        private fun scrollStep(
            direction: String,
            action: UserAction,
            actions: List<UserAction>,
            next: Int,
        ): Int {
            nodes +=
                ToolNode(
                    id = nextId(),
                    tool = TOOL_SCROLL,
                    args = obj(ARG_DIRECTION to direction),
                    anchor = anchorOf(action),
                )
            var cursor = next
            while (actions.getOrNull(cursor)?.type == action.type) cursor++
            return cursor
        }

        private fun windowChanged(
            action: UserAction,
            actions: List<UserAction>,
            next: Int,
        ): Int {
            if (action.packageName != null && action.packageName != previousPackage) {
                nodes += settleWait()
                previousPackage = action.packageName
            }
            return skipWindowEvents(actions, next)
        }

        /** 跳过紧跟的连续窗口切换事件，返回第一个非窗口切换操作的下标。 */
        private fun skipWindowEvents(
            actions: List<UserAction>,
            from: Int,
        ): Int {
            var cursor = from
            while (actions.getOrNull(cursor)?.type == UserActionType.WINDOW_CHANGED) cursor++
            return cursor
        }

        /** 目标：文字 / 描述优先（输入框不用文字，因为输入后文字会变化），最后用比例坐标后备。 */
        private fun targetOf(
            action: UserAction,
            asInput: Boolean,
        ): Target? {
            val fallback = fallbackOf(action)
            val text = action.text?.takeIf { it.isNotBlank() }
            val desc = action.contentDescription?.takeIf { it.isNotBlank() }
            return when {
                !asInput && text != null ->
                    Target(
                        by = TargetBy.TEXT,
                        value = text,
                        match = MatchMode.EQUALS,
                        fallback = fallback,
                    )
                !asInput && desc != null ->
                    Target(
                        by = TargetBy.DESC,
                        value = desc,
                        match = MatchMode.EQUALS,
                        fallback = fallback,
                    )
                fallback != null -> Target(by = TargetBy.TEXT, value = null, fallback = fallback)
                else -> null
            }
        }

        private fun fallbackOf(action: UserAction): TargetFallback? {
            val b = action.bounds ?: return null
            val x = ((b.l + b.r) / 2) * PERMILLE / screenWidth
            val y = ((b.t + b.b) / 2) * PERMILLE / screenHeight
            return TargetFallback(at = listOf(x, y))
        }

        private fun anchorOf(action: UserAction): Anchor? = action.packageName?.let { Anchor(app = it) }

        private fun settleWait(): WaitNode = WaitNode(id = nextId(), waitFor = WaitFor.TIME, ms = settle)

        private fun nextId(): String = "n${nodes.size + 1}"

        private fun obj(vararg pairs: Pair<String, Any>): JsonObject =
            JsonObject(
                pairs.associate { (k, v) ->
                    k to
                        when (v) {
                            is Boolean -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                },
            )
    }
}
