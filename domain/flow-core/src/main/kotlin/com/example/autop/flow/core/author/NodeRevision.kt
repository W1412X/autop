/**
 * 用一次成功动作修订单个节点（docs/design/flows-precondition-selfrepair.md S1）。
 *
 * 与 `TrajectoryConverter` 的整份生成不同：这里只替换节点的动作与目标，保留 id、
 * 失败处理、前置断言、期望效果等语义字段，节点集合结构不变。
 */
package com.example.autop.flow.core.author

import com.example.autop.agent.ReplayableAction
import com.example.autop.flow.ToolNode

/** 节点修订转换器。 */
public object NodeRevision {
    /** 回放记录里的工具 id 前缀（`tool.tap`）。 */
    private const val TOOL_PREFIX = "tool."

    /**
     * 按 [original] 的 id 生成修订后的节点。
     *
     * 只替换 `tool` / `args` / `target` / `target2`，其余字段（含 `pre` / `expect` / `onFail`）原样保留。
     * [steps] 由调用方保证只含成功动作；这里**只取最后一个**，整段轨迹会把导航步骤编进目标节点。
     * 无可用动作时返回 null（不生成修订）。
     */
    public fun revise(
        original: ToolNode,
        steps: List<ReplayableAction>,
    ): ToolNode? {
        val step = steps.lastOrNull() ?: return null
        val tool = step.toolId.removePrefix(TOOL_PREFIX)
        if (tool.isEmpty()) return null
        val args = step.replayArgs
        val isTap = tool == TrajectoryConverter.TOOL_TAP
        return original.copy(
            tool = tool,
            args = HitTarget.publicArgs(args, dropCoords = isTap),
            target = if (isTap) HitTarget.targetOf(args) else original.target,
            target2 = original.target2,
        )
    }
}
