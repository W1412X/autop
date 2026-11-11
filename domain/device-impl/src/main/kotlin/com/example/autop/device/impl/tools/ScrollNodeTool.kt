/**
 * `tool.scroll_node`（coordinate-node-action-split §2.2、§5.5）：对可滚动节点执行无障碍
 * 滚动动作（方向化 ACTION_SCROLL_*），每步后等稳定并判断是否移动，到头提前停止；
 * 不补发手势。回放记录写成等价 `tool.scroll` 坐标（§5.5）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.Point
import com.example.autop.device.ScreenNode
import com.example.autop.device.core.HitResolver
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.device.core.ScrollDirection
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.nodeaction.NodeActionIds
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.FrameMotion
import com.example.autop.device.impl.observation.ReplayInput
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class NodeScrollAction(
    val id: Int,
    val target: ResolvedTarget,
    val direction: ScrollDirection,
    val steps: Int,
    val replayArgs: JsonObject,
)

/** 节点滚动工具。 */
@Singleton
public class ScrollNodeTool
    @Inject
    constructor(
        private val resolver: NodeTargetResolver,
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val frameMotion: FrameMotion,
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.scrollNode

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val nodeId = args.int("id")
            if (nodeId == null || nodeId < 1) return invalidArg("id 必须是 ≥1 的整数（来自 a11y 观测的节点 id）")
            val direction =
                ScrollDirection.of(args.str("direction"))
                    ?: return invalidArg("direction 必须是 up、down、left、right 之一")
            val got =
                resolver.resolve(
                    ctx,
                    nodeId,
                    requiredAct = ScreenJsonWire.ACT_SCROLL,
                    requiredActionId = NodeActionIds.scrollActionOf(direction),
                    notActionableCode = DeviceErrorCodes.NOT_SCROLLABLE,
                )
            return when (got) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepared(got.value, nodeId, direction, args, ctx)
            }
        }

        private fun prepared(
            target: ResolvedTarget,
            nodeId: Int,
            direction: ScrollDirection,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val subject =
                subjects.build(
                    ID,
                    target.snapshot,
                    target.live,
                    mapOf(EXTRA_DIRECTION to direction.id),
                    ctx.config,
                )
            val maxSteps = ctx.config[DeviceConfig.SCROLL].maxSteps
            val steps = (args.int("steps") ?: 1).coerceIn(1, maxSteps)
            val action =
                NodeScrollAction(
                    id = nodeId,
                    target = target,
                    direction = direction,
                    steps = steps,
                    replayArgs = replayArgsOf(target.observed, direction, steps),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val scroll = action.payload as NodeScrollAction
            val actionId = NodeActionIds.scrollActionOf(scroll.direction)
            val label = NodeActionIds.scrollLabelOf(scroll.direction)
            var before = frameMotion.capture()
            var done = 0
            var reachedEnd = false
            while (done < scroll.steps && !reachedEnd) {
                val outcome =
                    gestures.performAtPath(
                        scroll.target.target.windowId,
                        scroll.target.target.path,
                        scroll.target.live.bounds,
                        actionId,
                    )
                when (outcome) {
                    NodeActionOutcome.STALE ->
                        return errorOutput(
                            AppError(
                                code = DeviceErrorCodes.NODE_STALE,
                                message = "node #${scroll.id} is stale in the live tree",
                                retryable = true,
                                hint = prompts.text(PROMPT_STALE),
                            ),
                        )
                    NodeActionOutcome.REJECTED ->
                        return errorOutput(
                            AppError(
                                code = DeviceErrorCodes.NODE_REJECTED,
                                message = "app rejected $label on node #${scroll.id}",
                                retryable = false,
                                hint = rejectedHint(scroll, label),
                            ),
                        )
                    NodeActionOutcome.DONE -> Unit
                }
                done += 1
                epilogue.awaitStable(ctx)
                val after = frameMotion.capture()
                reachedEnd = movedOf(frameMotion, ctx, before, after) == false
                before = after ?: before
            }
            val out =
                epilogue.finish(
                    ctx,
                    resultText(scroll, done, reachedEnd),
                    null,
                    ReplayInput(TOOL_SCROLL_ID, scroll.replayArgs, gesture = null),
                )
            return out.copy(content = out.content + Block.Text(trace.executionLine(ExecMethod.NODE_ACTION, label)))
        }

        /** 结果：节点滚动汇总行 + 复用的滚动汇总（steps / 已到头）。 */
        private fun resultText(
            scroll: NodeScrollAction,
            done: Int,
            reachedEnd: Boolean,
        ): String {
            val head =
                prompts.text(
                    PROMPT_SCROLL_RESULT,
                    mapOf(VAR_ID to scroll.id.toString(), VAR_DIRECTION to scroll.direction.id),
                )
            val summary =
                prompts.text(
                    PROMPT_SCROLL_SUMMARY,
                    mapOf(VAR_STEPS to done.toString(), VAR_REACHED_END to if (reachedEnd) "1" else ""),
                )
            return "$head\n$summary"
        }

        /** 拒绝提示（§8）：带动作名与节点 at，让模型决定是否改用 scroll。 */
        private fun rejectedHint(
            scroll: NodeScrollAction,
            label: String,
        ): String {
            val at = atOf(scroll.target.observed)
            return prompts.text(
                PROMPT_REJECTED,
                mapOf(VAR_ACTION to label, VAR_AT to "${at.x},${at.y}"),
            )
        }

        /** 回放参数（§5.5）：等价 `tool.scroll` 坐标 + `_hit`；id 不进回放。 */
        private fun replayArgsOf(
            observed: ScreenNode,
            direction: ScrollDirection,
            steps: Int,
        ): JsonObject {
            val at = atOf(observed)
            return buildJsonObject {
                put("direction", direction.id)
                put("steps", steps)
                put("x", at.x)
                put("y", at.y)
                put("_hit", HitResolver.describeNode(observed))
            }
        }

        /** 节点推荐落点（0–1000）；缺失时取 bounds 中心。 */
        private fun atOf(observed: ScreenNode): Point =
            observed.at
                ?: Point(
                    (observed.bounds.l + observed.bounds.r) / 2,
                    (observed.bounds.t + observed.bounds.b) / 2,
                )

        private companion object {
            const val ID = "tool.scroll_node"
            const val TOOL_SCROLL_ID = "tool.scroll"
            const val EXTRA_DIRECTION = "direction"

            const val PROMPT_SCROLL_RESULT = "device.node.scroll_result"
            const val PROMPT_SCROLL_SUMMARY = "device.scroll.summary"
            const val PROMPT_REJECTED = "device.hint.node_rejected"
            const val PROMPT_STALE = "device.hint.node_stale"

            const val VAR_ID = "id"
            const val VAR_DIRECTION = "direction"
            const val VAR_ACTION = "action"
            const val VAR_AT = "at"
            const val VAR_STEPS = "steps"
            const val VAR_REACHED_END = "reachedEnd"
        }
    }
