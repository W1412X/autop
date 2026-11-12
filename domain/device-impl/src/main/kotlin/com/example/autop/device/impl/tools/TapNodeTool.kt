/**
 * `tool.tap_node`（coordinate-node-action-split §2.2、§5.5）：对 a11y 观测里带 id 的节点
 * 执行无障碍点击动作（ACTION_CLICK / ACTION_LONG_CLICK），不是触摸，不补发手势。
 * 应用拒绝/节点失效如实报错（附节点 at，由模型决定是否改用 `tap`）；回放记录写成等价
 * `tool.tap` 坐标（§5.5）。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.Point
import com.example.autop.device.ScreenNode
import com.example.autop.device.core.HitResolver
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.nodeaction.NodeActionIds
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class NodeTapAction(
    val id: Int,
    val target: ResolvedTarget,
    val long: Boolean,
    val waitMs: Long?,
    val resultText: String,
    val replayArgs: JsonObject,
)

/** 节点点击工具。 */
@Singleton
public class TapNodeTool
    @Inject
    constructor(
        private val resolver: NodeTargetResolver,
        private val gestures: GestureController,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.tapNode

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val nodeId = args.int("id")
            if (nodeId == null || nodeId < 1) return invalidArg("id 必须是 ≥1 的整数（来自 a11y 观测的节点 id）")
            val long = args.bool("long") == true
            val requiredAct = if (long) ScreenJsonWire.ACT_LONG_PRESS else ScreenJsonWire.ACT_CLICK
            val requiredActionId = if (long) NodeActionIds.LONG_CLICK else NodeActionIds.CLICK
            val got =
                resolver.resolve(
                    ctx,
                    nodeId,
                    requiredAct = requiredAct,
                    requiredActionId = requiredActionId,
                    notActionableCode = DeviceErrorCodes.INVALID_ARGUMENT,
                )
            return when (got) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepared(got.value, nodeId, long, args, ctx)
            }
        }

        private fun prepared(
            target: ResolvedTarget,
            nodeId: Int,
            long: Boolean,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val subject = subjects.build(ID, target.snapshot, target.live, emptyMap(), ctx.config)
            val label = if (long) ACTION_LONG_CLICK else ACTION_CLICK
            val action =
                NodeTapAction(
                    id = nodeId,
                    target = target,
                    long = long,
                    waitMs = args.long("wait_ms"),
                    resultText =
                        prompts.text(
                            PROMPT_NODE_RESULT,
                            mapOf(
                                VAR_ID to nodeId.toString(),
                                VAR_ACTION to label,
                                VAR_RECEIPT to HitResolver.nodeReceipt(target.observed),
                            ),
                        ),
                    replayArgs = replayArgsOf(target.observed, long),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val tap = action.payload as NodeTapAction
            val pre = epilogue.preSample(ctx)
            val label = if (tap.long) ACTION_LONG_CLICK else ACTION_CLICK
            val actionId = if (tap.long) NodeActionIds.LONG_CLICK else NodeActionIds.CLICK
            val outcome =
                gestures.performAtPath(
                    tap.target.target.windowId,
                    tap.target.target.path,
                    tap.target.live.bounds,
                    actionId,
                )
            when (outcome) {
                NodeActionOutcome.STALE ->
                    return errorOutput(
                        AppError(
                            code = DeviceErrorCodes.NODE_STALE,
                            message = "node #${tap.id} is stale in the live tree",
                            retryable = true,
                            hint = prompts.text(PROMPT_STALE),
                        ),
                    )
                NodeActionOutcome.REJECTED ->
                    return errorOutput(
                        AppError(
                            code = DeviceErrorCodes.NODE_REJECTED,
                            message = "app rejected $label on node #${tap.id}",
                            retryable = false,
                            hint = rejectedHint(tap, label),
                        ),
                    )
                NodeActionOutcome.DONE -> Unit
            }
            return epilogue.finish(
                ctx,
                tap.resultText,
                tap.waitMs,
                ReplayInput(TOOL_TAP_ID, tap.replayArgs, gesture = null),
                pre,
                trace = listOf(trace.executionLine(ExecMethod.NODE_ACTION, label)),
            )
        }

        /** 拒绝提示（§8）：带动作名与节点 at，让模型决定是否改用 tap。 */
        private fun rejectedHint(
            tap: NodeTapAction,
            label: String,
        ): String {
            val at = atOf(tap.target.observed)
            return prompts.text(
                PROMPT_REJECTED,
                mapOf(VAR_ACTION to label, VAR_AT to "${at.x},${at.y}"),
            )
        }

        /** 回放参数（§5.5）：等价 `tool.tap` 坐标 + `_hit`；id 不进回放（只对单次观测有效）。 */
        private fun replayArgsOf(
            observed: ScreenNode,
            long: Boolean,
        ): JsonObject {
            val at = atOf(observed)
            return buildJsonObject {
                put("x", at.x)
                put("y", at.y)
                if (long) put("long", true)
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
            const val ID = "tool.tap_node"
            const val TOOL_TAP_ID = "tool.tap"

            const val ACTION_CLICK = "ACTION_CLICK"
            const val ACTION_LONG_CLICK = "ACTION_LONG_CLICK"

            const val PROMPT_NODE_RESULT = "device.node.result"
            const val PROMPT_REJECTED = "device.hint.node_rejected"
            const val PROMPT_STALE = "device.hint.node_stale"

            const val VAR_ID = "id"
            const val VAR_ACTION = "action"
            const val VAR_RECEIPT = "receipt"
            const val VAR_AT = "at"
        }
    }
