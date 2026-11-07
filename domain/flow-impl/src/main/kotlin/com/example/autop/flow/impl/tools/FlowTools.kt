/**
 * 智能体侧流程工具（docs/design/flows.md §14）：`run_flow` 运行已有流程并取回结果，
 * `list_flows` 列出可用流程。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunLineage
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.RunId
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowRunStore
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowStore
import com.example.autop.flow.core.ParamBinder
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.llm.Block
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** 流程工具规格。 */
public object FlowToolSpecs {
    /** tool.run_flow：运行流程并返回结果。 */
    public val runFlow: ToolSpec =
        toolSpec(name = "run_flow", descriptionPromptId = "tool.run_flow") {
            string("flow", required = true)
            anyObject("params")
            string("start_at")
        }

    /** tool.list_flows：列出可用流程。 */
    public val listFlows: ToolSpec = toolSpec(name = "list_flows", descriptionPromptId = "tool.list_flows") {}

    /** tool.update_flow：用本次运行的成功操作生成既有流程的修订草稿。 */
    public val updateFlow: ToolSpec =
        toolSpec(name = "update_flow", descriptionPromptId = "tool.update_flow") {
            string("flow", required = true)
            string("reason", required = true)
        }

    /** tool.save_as_flow：把本次运行中成功的操作保存为待确认的流程草稿。 */
    public val saveAsFlow: ToolSpec =
        toolSpec(name = "save_as_flow", descriptionPromptId = "tool.save_as_flow") {
            string("name", required = true)
            string("description")
        }

    /** tool.patch_flow_node：用本次运行中成功的动作修订某个流程的单个节点。 */
    public val patchFlowNode: ToolSpec =
        toolSpec(name = "patch_flow_node", descriptionPromptId = "tool.patch_flow_node") {
            string("flow", required = true)
            string("node_id", required = true)
            string("reason", required = true)
        }
}

internal fun failure(
    code: String,
    message: String,
    hint: String? = null,
): ToolOutput =
    ToolOutput(
        ok = false,
        content = listOf(Block.Text(message)),
        error = AppError(code = code, message = message, hint = hint),
    )

/** 运行一个流程。 */
@Singleton
public class RunFlowTool
    @Inject
    internal constructor(
        private val store: FlowStore,
        private val session: FlowSession,
        private val threads: ThreadStore,
        private val lineage: RunLineage,
        private val ids: IdGenerator,
    ) : Tool {
        override val id: String = "tool.run_flow"
        override val spec: ToolSpec = FlowToolSpecs.runFlow

        // 流程内部有自己的工具路由与锁；这里按"写系统"串行化，避免与其他写工具并行。
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val name = (args["flow"] as? JsonPrimitive)?.content.orEmpty()
            val stored =
                store.resolve(name) ?: return failure(FlowErrorCodes.NOT_FOUND, "找不到流程：$name", "先调用 list_flows 查看可用流程")
            val flow = stored.definition
            val params =
                try {
                    ParamBinder.bind(flow.params, args["params"] as? JsonObject ?: JsonObject(emptyMap()))
                } catch (e: FlowException) {
                    return failure(e.code, e.message.orEmpty(), "按 list_flows 返回的参数声明补齐参数")
                }
            val runId = RunId(ids.newId())
            val thread = threads.createThread(title = flow.name, parent = null)
            lineage.register(runId, ctx.runId)
            try {
                val request =
                    FlowRunRequest(
                        runId = runId,
                        threadId = thread,
                        flow = flow,
                        params = params,
                        origin = ORIGIN_AGENT,
                        options =
                            FlowRunOptions(
                                startAt = (args["start_at"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                            ),
                        config = ctx.config,
                    )
                val finished = coroutineScope { session.start(request, this).await() }
                return result(finished)
            } finally {
                lineage.unregister(runId)
            }
        }

        /** 失败时附出错节点与原因（flows.md §14.1）。 */
        private fun result(finished: RunFinished): ToolOutput {
            val ok = finished.status == RunOutcome.SUCCEEDED
            val body =
                buildJsonObject {
                    put("status", finished.status.name.lowercase())
                    finished.summary?.let { put("summary", it) }
                    finished.outputs?.let { put("outputs", it) }
                    finished.errorCode?.let { put("errorCode", it) }
                    finished.failedNodeId?.let { put("failedNodeId", it) }
                    finished.failedNodeName?.let { put("failedNodeName", it) }
                }
            return ToolOutput(
                ok = ok,
                content = listOf(Block.Text(body.toString())),
                data = body,
                error =
                    if (ok) {
                        null
                    } else {
                        AppError(
                            code = finished.errorCode ?: FlowErrorCodes.AGENT_FAILED,
                            message = finished.summary.orEmpty(),
                        )
                    },
            )
        }

        private companion object {
            const val ORIGIN_AGENT = "agent"
        }
    }

/** 列出可用流程。 */
@Singleton
public class ListFlowsTool
    @Inject
    internal constructor(
        private val store: FlowStore,
        private val runs: FlowRunStore,
    ) : Tool {
        override val id: String = "tool.list_flows"
        override val spec: ToolSpec = FlowToolSpecs.listFlows
        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            // FlowListItem 带最近一次运行的状态与时间（§14.2），定义细节从 listAll 取。
            val current = store.observeAll().first()
            val items = current.associateBy { it.id }
            val list =
                buildJsonArray {
                    for (stored in store.listAll()) {
                        val flow = stored.definition
                        val stats = runs.stats(stored.id.value)
                        add(
                            buildJsonObject {
                                put("name", flow.name)
                                put("description", flow.description)
                                put(
                                    "params",
                                    buildJsonArray {
                                        flow.params.forEach { p ->
                                            add(
                                                buildJsonObject {
                                                    put("name", p.name)
                                                    put(
                                                        "type",
                                                        AutopJson.default
                                                            .encodeToString(
                                                                com.example.autop.flow.ParamType
                                                                    .serializer(),
                                                                p.type,
                                                            ).trim('"'),
                                                    )
                                                    put("required", p.required)
                                                    if (p.description.isNotEmpty()) put("description", p.description)
                                                },
                                            )
                                        }
                                    },
                                )
                                put("runs", stats.totalRuns)
                                put("successRate", stats.successRate)
                                items[stored.id]?.let { item ->
                                    item.lastRunAt?.let { put("lastRunAt", it) }
                                    item.lastRunStatus?.let { put("lastRunStatus", it) }
                                }
                            },
                        )
                    }
                }
            return ToolOutput(ok = true, content = listOf(Block.Text(list.toString())), data = list)
        }
    }
