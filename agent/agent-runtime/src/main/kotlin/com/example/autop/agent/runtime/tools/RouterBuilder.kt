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
) {
    /** 当前 modelRole 的模型是否支持图片输入。 */
    fun modelVisionOf(request: RunRequest): Boolean =
        llm
            .forRole(request.modelRole)
            .getOrNull()
            ?.profile
            ?.capabilities
            ?.vision ?: false

    /** 过滤本次可见工具后建路由器。 */
    fun build(
        request: RunRequest,
        toolFilter: Set<String>?,
        modelVision: Boolean,
    ): ToolRouter {
        val capabilities = Capability.entries.filterTo(mutableSetOf()) { platform.has(it) }
        val visible =
            ToolRouter.visibleTools(
                tools = tools.toList(),
                enabled = request.config[AgentConfig.TOOLS].enabled.toSet(),
                toolFilter = toolFilter,
                capabilities = capabilities,
                modelVision = modelVision,
            )
        return ToolRouter(
            tools = visible,
            executor = ToolExecutor(visible, policy, audit, prompts, clock),
            enrichers = enrichers.toList(),
            prompts = prompts,
        )
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
    ): ToolInvoker = RouterToolInvoker(builder.build(request, toolFilter, builder.modelVisionOf(request)))
}
