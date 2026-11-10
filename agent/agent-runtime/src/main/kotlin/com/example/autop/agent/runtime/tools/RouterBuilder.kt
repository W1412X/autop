/**
 * 路由器构造（docs/tech/04 §3.2，docs/design/flows.md §8.2）：按配置启用清单、Run 过滤、
 * 设备能力与模型视觉能力过滤可见工具，组装 [ToolRouter]；智能体 Run 与
 * [RuntimeToolInvokerFactory]（流程等）共用。
 */
package com.example.autop.agent.runtime.tools

import com.example.autop.agent.RunRequest
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAuditSink
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolResultEnricher
import com.example.autop.core.common.Capability
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmGateway
import com.example.autop.policy.PolicyEngine
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/** 路由器构造器（无状态，可共享）。 */
internal class RouterBuilder(
    private val tools: Set<Tool>,
    private val enrichers: Set<ToolResultEnricher>,
    private val llm: LlmGateway,
    private val prompts: PromptRepository,
    private val clock: Clock,
    private val policy: PolicyEngine,
    private val platform: PlatformCapabilities,
    private val audit: ToolAuditSink?,
    private val logger: Logger,
) {
    /** 当前 modelRole 的模型是否支持图片输入。 */
    fun modelVisionOf(request: RunRequest): Boolean =
        llm
            .forRole(request.modelRole)
            .getOrNull()
            ?.profile
            ?.capabilities
            ?.vision ?: false

    /** 过滤本次可见工具后建路由器，附带被过滤掉的工具及原因（WP3 运行状态）。 */
    fun build(
        request: RunRequest,
        toolFilter: Set<String>?,
        modelVision: Boolean,
    ): RouterBuild {
        val capabilities = Capability.entries.filterTo(mutableSetOf()) { platform.has(it) }
        val enabled = request.config[AgentConfig.TOOLS].enabled.toSet()
        val hidden = mutableListOf<HiddenTool>()
        val visible =
            tools.filter { tool ->
                val reason = hiddenReasonOf(tool, enabled, toolFilter, capabilities, modelVision)
                if (reason == null) {
                    true
                } else {
                    hidden += HiddenTool(tool.spec.name, reason)
                    false
                }
            }
        val router =
            ToolRouter(
                tools = visible,
                executor = ToolExecutor(visible, policy, audit, prompts, clock, logger),
                enrichers = enrichers.toList(),
                prompts = prompts,
            )
        return RouterBuild(router, hidden)
    }

    /** 首个不可见原因；null = 可见（与 [ToolRouter.visibleTools] 同一判定顺序）。 */
    private fun hiddenReasonOf(
        tool: Tool,
        enabled: Set<String>,
        toolFilter: Set<String>?,
        capabilities: Set<Capability>,
        modelVision: Boolean,
    ): String? =
        when {
            tool.id !in enabled -> HiddenTool.DISABLED
            toolFilter != null && tool.id !in toolFilter -> HiddenTool.FILTERED
            !tool.requiredCapabilities.all { it in capabilities } -> HiddenTool.CAPABILITY
            tool.requiresModelVision && !modelVision -> HiddenTool.VISION
            else -> null
        }
}

/** 过滤结果：可见路由器 + 被过滤掉的工具及原因（WP3）。 */
internal data class RouterBuild(
    val router: ToolRouter,
    val hidden: List<HiddenTool>,
)

/** 本次不可用的一个工具。[reason] 见伴生常量。 */
internal data class HiddenTool(
    val name: String,
    val reason: String,
) {
    internal companion object {
        const val DISABLED = "disabled"
        const val FILTERED = "filtered"
        const val CAPABILITY = "capability"
        const val VISION = "vision"
    }
}

/** 把路由器包装成 [ToolInvoker]（schema 校验、读写锁、超时、策略、审计照常）。 */
internal class RouterToolInvoker(
    private val router: ToolRouter,
) : ToolInvoker {
    private val sequence = AtomicLong()

    override suspend fun invoke(
        toolName: String,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolOutput = router.invokeByName("inv_${sequence.incrementAndGet()}", toolName, args, ctx)
}

/** [ToolInvokerFactory] 实现：每次 `create` 一个独立锁域的调用器。 */
internal class RuntimeToolInvokerFactory(
    private val builder: RouterBuilder,
) : ToolInvokerFactory {
    override fun create(
        request: RunRequest,
        toolFilter: Set<String>?,
    ): ToolInvoker = RouterToolInvoker(builder.build(request, toolFilter, builder.modelVisionOf(request)).router)
}
