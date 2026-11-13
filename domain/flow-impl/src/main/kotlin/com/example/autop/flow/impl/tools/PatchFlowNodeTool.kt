/**
 * `tool.patch_flow_node`（docs/design/flows-precondition-selfrepair.md S2）：智能体接手把流程里
 * 某一步做完后，用这一次成功的动作修订该节点，生成关联原流程的修订草稿。
 *
 * 与 `tool.update_flow` 的区别：后者用整段轨迹重建整个流程，适合"完整重做了一遍任务"；
 * 本工具只替换失败那一个节点的动作与目标，保留 id、失败处理、前置断言与期望效果。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.BetaFeature
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowStore
import com.example.autop.flow.IfNode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.ToolNode
import com.example.autop.flow.core.author.NodeRevision
import com.example.autop.flow.impl.author.FlowDraftWriter
import com.example.autop.flow.impl.service.FlowRevisionService
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 生成某流程单个节点的修订草稿。 */
@Singleton
public class PatchFlowNodeTool
    @Inject
    internal constructor(
        private val threads: ThreadStore,
        private val store: FlowStore,
        private val writer: FlowDraftWriter,
        private val revisions: FlowRevisionService,
        private val configProvider: ConfigProvider,
        private val logger: Logger,
    ) : Tool {
        override val id: String = "tool.patch_flow_node"
        override val spec: ToolSpec = FlowToolSpecs.patchFlowNode
        override val betaFeature: BetaFeature?
            get() = BetaFeature.FLOWS

        // 写入草稿表：与其他写系统工具串行。
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val name = args.text("flow").orEmpty()
            val stored =
                store.resolve(name)
                    ?: return failure(FlowErrorCodes.NOT_FOUND, "找不到流程：$name", "先调用 list_flows 查看可用流程")
            val nodeId = args.text("node_id").orEmpty()
            val original =
                findNode(stored.definition.steps, nodeId) as? ToolNode
                    ?: return failure(FlowErrorCodes.NOT_FOUND, "找不到节点：$nodeId", "只有 tool 节点支持修订")
            // 本 Run 里成功的工具动作；只取最后一次（整段轨迹会把导航步骤编进目标节点）。
            val steps =
                threads
                    .events(ctx.threadId, 0, Long.MAX_VALUE)
                    .filter { it.runId == ctx.runId }
                    .mapNotNull { (it.payload as? ToolResultEvent)?.takeIf { r -> r.ok }?.replay }
            val revised =
                NodeRevision.revise(original, steps)
                    ?: return failure(FlowErrorCodes.INVALID, "本次运行没有可用于修订的操作", "只有成功执行的界面操作会被使用")
            val revisedFlow = stored.definition.copy(steps = replaceNode(stored.definition.steps, nodeId, revised))
            val reason = args.text("reason").orEmpty()
            // 自动修订开关（flow.recovery.autoPatch）：开时跳过用户确认，直接写版本并推给运行中的实例。
            return if (configProvider.current()[FlowConfig.RECOVERY].autoPatch) {
                autoPatch(stored.definition.name, stored.id.value, nodeId, revisedFlow, reason)
            } else {
                createDraft(stored.definition.name, stored.id.value, nodeId, revisedFlow, reason)
            }
        }

        /** 生成修订草稿，等用户确认后生效。 */
        private suspend fun createDraft(
            flowName: String,
            flowId: String,
            nodeId: String,
            revisedFlow: FlowDefinition,
            reason: String,
        ): ToolOutput =
            when (val saved = writer.save(revisedFlow, baseFlowId = flowId, reason = reason)) {
                is Outcome.Err -> failure(saved.error.code, saved.error.message, saved.error.hint)
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content =
                            listOf(
                                Block.Text(
                                    "已生成流程「$flowName」节点 $nodeId 的修订草稿" +
                                        "(draftId=${saved.value})，原因：$reason。" +
                                        "用户确认后生效，原定义会保留为历史版本。",
                                ),
                            ),
                        data = draftData(saved.value, flowId = flowId),
                    )
            }

        /** 自动修订：直接写入新版本（旧定义进版本库），并推给正在运行的实例。 */
        private suspend fun autoPatch(
            flowName: String,
            flowId: String,
            nodeId: String,
            revisedFlow: FlowDefinition,
            reason: String,
        ): ToolOutput {
            logger.w(TAG, "flow_auto_patch", "flow" to flowId, "node" to nodeId, "reason" to reason)
            return when (val applied = revisions.apply(flowId, revisedFlow, reason)) {
                is Outcome.Err -> failure(applied.error.code, applied.error.message, applied.error.hint)
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content =
                            listOf(
                                Block.Text(
                                    "已自动修订流程「$flowName」节点 $nodeId，原因：$reason。" +
                                        "原定义已保留为历史版本，运行中的流程已用新定义继续。",
                                ),
                            ),
                        data = flowData(flowId),
                    )
            }
        }

        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        /** 在步骤树（含分支与循环）里查找节点。 */
        private fun findNode(
            steps: List<Node>,
            nodeId: String,
        ): Node? {
            for (n in steps) {
                if (n.id == nodeId) return n
                val children =
                    when (n) {
                        is IfNode -> n.thenSteps + n.elseSteps
                        is SwitchNode -> n.cases.flatMap { it.steps } + n.default
                        is LoopNode -> n.steps
                        else -> emptyList()
                    }
                findNode(children, nodeId)?.let { return it }
            }
            return null
        }

        /** 替换步骤树里的节点，返回新树（节点不存在时原样返回）。 */
        private fun replaceNode(
            steps: List<Node>,
            nodeId: String,
            replacement: Node,
        ): List<Node> =
            steps.map { n ->
                if (n.id == nodeId) {
                    replacement
                } else {
                    when (n) {
                        is IfNode ->
                            n.copy(
                                thenSteps = replaceNode(n.thenSteps, nodeId, replacement),
                                elseSteps = replaceNode(n.elseSteps, nodeId, replacement),
                            )
                        is SwitchNode ->
                            n.copy(
                                cases =
                                    n.cases.map { c ->
                                        c.copy(steps = replaceNode(c.steps, nodeId, replacement))
                                    },
                                default = replaceNode(n.default, nodeId, replacement),
                            )
                        is LoopNode -> n.copy(steps = replaceNode(n.steps, nodeId, replacement))
                        else -> n
                    }
                }
            }

        private companion object {
            const val TAG = "PatchFlowNode"
        }
    }
