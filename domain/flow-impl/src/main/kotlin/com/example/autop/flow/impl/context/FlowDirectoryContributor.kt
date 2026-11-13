/**
 * `context.flow_directory`（docs/design/flows-recording-recovery.md A2）：把可用流程列成目录放进系统上下文，
 * 让智能体在开始任务前先匹配已有流程。按成功率从高到低排序，数量与字数受 `flow.directory` 限制。
 */
package com.example.autop.flow.impl.context

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.ContextPart
import com.example.autop.agent.ContextRequest
import com.example.autop.agent.Placement
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.BetaFeature
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.config.FlowConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowRunStore
import com.example.autop.flow.FlowStore
import javax.inject.Inject
import javax.inject.Singleton

/** 流程目录上下文贡献者。 */
@Singleton
public class FlowDirectoryContributor
    @Inject
    constructor(
        private val store: FlowStore,
        private val runs: FlowRunStore,
        private val prompts: PromptRepository,
        private val configProvider: ConfigProvider,
    ) : ContextContributor {
        override val id: String = ID

        override suspend fun contribute(req: ContextRequest): ContextPart? {
            val snapshot = configProvider.current()
            // 流程是 Beta 功能（ADR-0030）：关闭时不向系统上下文贡献目录。
            if (!snapshot[FeaturesConfig.FEATURES].isEnabled(BetaFeature.FLOWS)) return null
            val all = store.listAll()
            if (all.isEmpty()) return null
            val cfg = snapshot[FlowConfig.DIRECTORY]
            val ranked =
                all
                    .map { it.definition to runs.stats(it.id.value).successRate }
                    .sortedByDescending { it.second }
            val shown = ranked.take(cfg.maxItems)
            val items = shown.joinToString("\n") { (flow, _) -> line(flow) }.take(cfg.maxChars)
            val omitted = all.size - shown.size
            val vars =
                mapOf(
                    "items" to items,
                    "omitted" to if (omitted > 0) omitted.toString() else "",
                )
            val text = (prompts.render(TEMPLATE, vars) as? Outcome.Ok)?.value?.text ?: return null
            return ContextPart(
                placement = Placement.SYSTEM_DYNAMIC,
                text = text,
                promptIds = runCatching { mapOf(TEMPLATE to prompts.get(TEMPLATE).version) }.getOrDefault(emptyMap()),
            )
        }

        private fun line(flow: FlowDefinition): String {
            val params = flow.params.joinToString(",") { it.name }.ifEmpty { "无" }
            val desc = flow.description.ifBlank { "无说明" }
            return "- ${flow.name} | $desc | 参数: $params"
        }

        private companion object {
            const val ID = "context.flow_directory"
            const val TEMPLATE = "agent.flow_directory"
        }
    }
