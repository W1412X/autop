/**
 * `tool.save_as_flow`（docs/design/flows-recording-recovery.md A3）：把本次运行中成功的操作转换成流程，
 * 保存为待确认草稿（`flow_drafts`）。草稿不会直接进入流程库，用户确认后才可用。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.core.author.TrajectoryConverter
import com.example.autop.flow.impl.author.FlowDraftWriter
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 保存当前运行为流程草稿。 */
@Singleton
public class SaveAsFlowTool
    @Inject
    internal constructor(
        private val threads: ThreadStore,
        private val writer: FlowDraftWriter,
        private val ids: IdGenerator,
        private val configProvider: ConfigProvider,
    ) : Tool {
        override val id: String = "tool.save_as_flow"
        override val spec: ToolSpec = FlowToolSpecs.saveAsFlow

        // 写入流程草稿表：属于“写系统”，与其他写工具串行。
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val name = (args["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (name.isEmpty()) return failure(FlowErrorCodes.ARGS_INVALID, "需要 name 参数", "给流程起一个简短的名称")
            val description = (args["description"] as? JsonPrimitive)?.content.orEmpty()
            val steps =
                threads
                    .events(ctx.threadId, 0, Long.MAX_VALUE)
                    .filter { it.runId == ctx.runId }
                    .mapNotNull { (it.payload as? ToolResultEvent)?.takeIf { r -> r.ok }?.replay }
            val save = configProvider.current()[FlowConfig.SAVE]
            val definition =
                TrajectoryConverter.convert(
                    flowId = ids.newId(),
                    name = name,
                    description = description,
                    steps = steps,
                    settleMinMs = save.settleMinMs,
                    settleMaxMs = save.settleMaxMs,
                )
            if (definition.steps.isEmpty()) {
                return failure(FlowErrorCodes.INVALID, "本次运行没有可以保存为流程的操作", "只有成功执行的界面操作会被保存")
            }
            return when (val saved = writer.save(definition)) {
                is Outcome.Err -> failure(saved.error.code, saved.error.message, saved.error.hint)
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content =
                            listOf(
                                Block.Text(
                                    "已生成待确认的流程草稿「$name」(draftId=${saved.value})。用户在“流程”页确认后即可使用。",
                                ),
                            ),
                    )
            }
        }
    }
