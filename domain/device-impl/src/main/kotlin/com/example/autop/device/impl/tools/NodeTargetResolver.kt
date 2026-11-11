/**
 * 节点工具的目标解析（coordinate-node-action-split §3.3）：id → 最近一次 a11y 观测的
 * 记录节点 → 实时树复核（[LiveNodeMatcher]）；策略检查与动作都针对实时节点。
 * 错误码与 hint 见 §8 表。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.LiveNodeMatcher
import com.example.autop.device.core.NodeTarget
import com.example.autop.device.core.NodeTargets
import com.example.autop.device.core.SnapshotIndex
import com.example.autop.device.impl.deviceHistory
import javax.inject.Inject
import javax.inject.Singleton

/** 解析结果：观测节点（id/at/act）＋实时树里的定位与快照。 */
public data class ResolvedTarget(
    /** 最近一次观测里的节点（id、at、act 来自它）。 */
    val observed: ScreenNode,
    /** 实时树里的节点定位（windowId + 子序号路径）。 */
    val target: NodeTarget,
    /** 实时屏幕快照（策略主体用）。 */
    val snapshot: ScreenSnapshot,
) {
    /** 实时节点。 */
    public val live: NodeSnapshot get() = target.node
}

/** 节点目标解析器。 */
@Singleton
public class NodeTargetResolver
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val prompts: PromptRepository,
    ) {
        /**
         * 把 [id] 解析为实时节点：只对最近一次 a11y 观测有效；[requiredAct] 为观测节点
         * 必须声明的动作词，[requiredActionId] 为实时节点必须支持的无障碍动作；
         * 不满足 [notActionableCode]（INVALID_ARGUMENT / NOT_SCROLLABLE）。
         */
        @Suppress("ReturnCount") // §8 错误表的各失败分支各自一个出口，是错误分流语义
        suspend fun resolve(
            ctx: ToolContext,
            id: Int,
            requiredAct: String,
            requiredActionId: Int,
            notActionableCode: String,
        ): Outcome<ResolvedTarget> {
            val last =
                ctx.deviceHistory().last
                    ?: return err(
                        DeviceErrorCodes.MODE_UNAVAILABLE,
                        "no observation yet",
                        retryable = true,
                        hint = prompts.text(PROMPT_NO_OBSERVATION),
                    )
            if (last.mode != ObserveMode.A11Y) {
                return err(
                    DeviceErrorCodes.MODE_UNAVAILABLE,
                    "last observation is ${last.mode.id}, which has no node ids",
                    retryable = true,
                    hint = prompts.text(PROMPT_MODE_UNAVAILABLE, mapOf(VAR_MODE to last.mode.id)),
                )
            }
            val model = last.screen.model
            val observed =
                model.flatten().firstOrNull { it.id == id }
                    ?: return err(
                        DeviceErrorCodes.NODE_NOT_FOUND,
                        "node id $id is not in the last observation",
                        retryable = true,
                        hint = prompts.text(PROMPT_NOT_FOUND, mapOf(VAR_ID to id.toString())),
                    )
            if (requiredAct !in observed.act) {
                return notActionable(notActionableCode, id, requiredAct, observed)
            }
            val index = SnapshotIndex(last.snapshot)
            val recordedNode =
                observed.ref?.let { index.node(it) }
                    ?: return err(
                        DeviceErrorCodes.NODE_STALE,
                        "node id $id lost its snapshot reference",
                        retryable = true,
                        hint = prompts.text(PROMPT_STALE),
                    )
            val recorded = NodeTargets.of(index, recordedNode)
            return liveMatchOf(id, observed, recorded, requiredAct, requiredActionId, notActionableCode)
        }

        /** 实时树复核（§8 后三条：空树、失效、实时节点无所需动作）。 */
        private suspend fun liveMatchOf(
            id: Int,
            observed: ScreenNode,
            recorded: NodeTarget,
            requiredAct: String,
            requiredActionId: Int,
            notActionableCode: String,
        ): Outcome<ResolvedTarget> {
            val live = screenReader.snapshotOrDegraded()
            if (live is Outcome.Err) return live
            check(live is Outcome.Ok)
            val target = LiveNodeMatcher.find(live.value, recorded)
            return when {
                live.value.nodes.isEmpty() ->
                    err(
                        DeviceErrorCodes.APP_TREE_EMPTY,
                        "live accessibility tree is empty",
                        retryable = true,
                        hint = prompts.text(PROMPT_TREE_EMPTY),
                    )
                target == null ->
                    err(
                        DeviceErrorCodes.NODE_STALE,
                        "node id $id no longer matches any live node",
                        retryable = true,
                        hint = prompts.text(PROMPT_STALE),
                    )
                requiredActionId !in target.node.actions ->
                    notActionable(notActionableCode, id, requiredAct, observed)
                else -> Outcome.Ok(ResolvedTarget(observed, target, live.value))
            }
        }

        private fun notActionable(
            code: String,
            id: Int,
            requiredAct: String,
            observed: ScreenNode,
        ): Outcome.Err =
            err(
                code,
                "node id $id has no $requiredAct action",
                retryable = false,
                hint =
                    prompts.text(
                        PROMPT_NOT_ACTIONABLE,
                        mapOf(VAR_ACT to observed.act.joinToString(",")),
                    ),
            )

        private fun err(
            code: String,
            message: String,
            retryable: Boolean,
            hint: String,
        ): Outcome.Err = Outcome.Err(AppError(code = code, message = message, retryable = retryable, hint = hint))

        private companion object {
            const val PROMPT_NO_OBSERVATION = "device.hint.node_no_observation"
            const val PROMPT_MODE_UNAVAILABLE = "device.hint.node_mode_unavailable"
            const val PROMPT_NOT_FOUND = "device.hint.node_not_found"
            const val PROMPT_NOT_ACTIONABLE = "device.hint.node_not_actionable"
            const val PROMPT_STALE = "device.hint.node_stale"
            const val PROMPT_TREE_EMPTY = "device.a11y.tree_empty_hint"

            const val VAR_MODE = "mode"
            const val VAR_ID = "id"
            const val VAR_ACT = "act"
        }
    }
