/**
 * `tool.update_flow`（flows-recording-recovery.md A4）：智能体手动完成了某个流程失败的部分后，
 * 用本次运行的成功操作生成该流程的修订草稿。草稿关联原流程，用户确认后保存为新版本，旧定义保留在 `flow_versions`。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowStore
import com.example.autop.flow.core.author.TrajectoryConverter
import com.example.autop.flow.impl.author.FlowDraftWriter
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 生成既有流程的修订草稿。 */
@Singleton
public class UpdateFlowTool
    @Inject
    internal constructor(
        private val threads: ThreadStore,
        private val store: FlowStore,
        private val writer: FlowDraftWriter,
        private val configProvider: ConfigProvider,
    ) : Tool {
        override val id: String = "tool.update_flow"
        override val spec: ToolSpec = FlowToolSpecs.updateFlow

        // 写入草稿表：与其他写系统工具串行。
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val name = (args["flow"] as? JsonPrimitive)?.content.orEmpty()
            val stored =
                store.resolve(name)
                    ?: return failure(FlowErrorCodes.NOT_FOUND, "找不到流程：$name", "先调用 list_flows 查看可用流程")
            val reason = (args["reason"] as? JsonPrimitive)?.content.orEmpty()
            val steps =
                threads
                    .events(ctx.threadId, 0, Long.MAX_VALUE)
                    .filter { it.runId == ctx.runId }
                    .mapNotNull { (it.payload as? ToolResultEvent)?.takeIf { r -> r.ok }?.replay }
            val save = configProvider.current()[FlowConfig.SAVE]
            val revised =
                TrajectoryConverter.convert(
                    flowId = stored.definition.id,
                    name = stored.definition.name,
                    description = stored.definition.description,
                    steps = steps,
                    settleMinMs = save.settleMinMs,
                    settleMaxMs = save.settleMaxMs,
                )
            if (revised.steps.isEmpty()) {
                return failure(FlowErrorCodes.INVALID, "本次运行没有可用于修订流程的操作", "只有成功执行的界面操作会被使用")
            }
            return when (val saved = writer.save(revised, baseFlowId = stored.id.value, reason = reason)) {
                is Outcome.Err -> failure(saved.error.code, saved.error.message, saved.error.hint)
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content =
                            listOf(
                                Block.Text(
                                    "已生成流程「${stored.definition.name}」的修订草稿(draftId=${saved.value})，原因：$reason。" +
                                        "用户确认后生效，原定义会保留为历史版本。",
                                ),
                            ),
                        data = draftData(saved.value, flowId = stored.id.value),
                    )
            }
        }
    }
