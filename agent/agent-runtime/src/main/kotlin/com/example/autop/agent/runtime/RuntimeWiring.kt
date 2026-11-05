/**
 * 运行期装配分组（docs/tech/04 §3.1）：Run 间共享的服务、扩展点多绑定集合
 * 与外部端口，供 DefaultAgentSession 组装每次 Run 的 Router/Engines/TurnLoop。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.Compactor
import com.example.autop.agent.ContextContributor
import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAuditSink
import com.example.autop.agent.ToolResultEnricher
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmGateway
import com.example.autop.policy.PolicyEngine

/** Run 间共享的服务端口与基础设施。 */
internal class RuntimeServices(
    val store: ThreadStore,
    val llm: LlmGateway,
    val prompts: PromptRepository,
    val config: ConfigProvider,
    val clock: Clock,
)

/** 扩展点多绑定集合（工具/渲染规则/压缩策略等）。 */
internal class RuntimeExtensions(
    val tools: Set<Tool>,
    val enrichers: Set<ToolResultEnricher>,
    val contributors: Set<ContextContributor>,
    val rules: Set<HistoryRenderRule>,
    val compactors: Set<Compactor>,
)

/** 外部端口；可空端口按缺省行为处理（docs/tech/04 §2.4）。 */
@Suppress("LongParameterList") // 端口装配面（DI 注入）的自然结果
internal class RuntimePorts(
    val policy: PolicyEngine,
    val platform: PlatformCapabilities,
    val interaction: UserInteraction?,
    val audit: ToolAuditSink?,
    val logger: Logger,
    /** 回放前状态采样（device-impl）；未绑定时跳过回放前缀（docs/tech/04 §3.12）。 */
    val replayProbe: ReplayStateProbe?,
    /** 回放目标重解析（device-impl）；未绑定时按记录坐标执行（§5.7）。 */
    val replayTargets: ReplayTargetResolver?,
)
