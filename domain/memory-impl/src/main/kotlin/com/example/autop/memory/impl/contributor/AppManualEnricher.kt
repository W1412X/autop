/**
 * `enricher.app_manual`（docs/tech/06 §3）：工具输出的 `observation.packageName` 在本 Run
 * 中首次出现，且 `/apps/{package}/_manual` 存在时，注入该手册摘要（模板
 * `memory.context.app_manual`），提示可用 memory_read 查看全文。已注入的包名记录在 runScope。
 */
package com.example.autop.memory.impl.contributor

import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEnricher
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppLauncher
import com.example.autop.llm.Block
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemoryStore
import javax.inject.Inject
import javax.inject.Singleton

/** App 手册注入 enricher。 */
@Singleton
public class AppManualEnricher
    @Inject
    constructor(
        private val store: MemoryStore,
        private val prompts: PromptRepository,
        private val appLauncher: AppLauncher,
    ) : ToolResultEnricher {
        override val id: String = "enricher.app_manual"

        @Suppress("ReturnCount") // 出口按“无观测/非首次/无手册/渲染失败”分支，是流程语义
        override suspend fun enrich(
            toolId: String,
            output: ToolOutput,
            ctx: ToolContext,
        ): List<Block> {
            val packageName = output.observation?.packageName ?: return emptyList()
            val injected = ctx.runScope.getOrPut(RUN_SCOPE_KEY) { mutableSetOf<String>() }
            if (!injected.add(packageName)) return emptyList()
            val manualPath =
                MemoryPath.ROOT
                    .child("apps")
                    .child(packageName)
                    .child("_manual")
            val manual = store.get(manualPath) ?: return emptyList()
            val appName =
                runCatching { appLauncher.listApps(null).firstOrNull { it.packageName == packageName }?.label }
                    .getOrNull() ?: packageName
            val rendered =
                when (
                    val r =
                        prompts.render(
                            ID_TEMPLATE,
                            mapOf(
                                "appName" to appName,
                                "path" to manualPath.value,
                                "summary" to manual.summary,
                            ),
                        )
                ) {
                    is Outcome.Ok -> r.value.text
                    is Outcome.Err -> return emptyList()
                }
            return listOf(Block.Text(rendered))
        }

        private companion object {
            const val ID_TEMPLATE: String = "memory.context.app_manual"
            const val RUN_SCOPE_KEY: String = "memory.app_manual.injected"
        }
    }
