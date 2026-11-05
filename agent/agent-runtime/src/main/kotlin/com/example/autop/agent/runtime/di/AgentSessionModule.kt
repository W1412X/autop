/**
 * `AgentSession` 装配（docs/tech/04 §2.6/§3.1）：组装内部 DefaultAgentSession。
 * `ThreadStore` 由 agent-store 提供，`UserInteraction`/`ToolAuditSink` 由 task-impl
 * 提供（WP6b），其余端口来自 core/llm/policy 各模块。
 */
package com.example.autop.agent.runtime.di

import com.example.autop.agent.AgentSession
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
import com.example.autop.agent.runtime.DefaultAgentSession
import com.example.autop.agent.runtime.RuntimeExtensions
import com.example.autop.agent.runtime.RuntimePorts
import com.example.autop.agent.runtime.RuntimeServices
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmGateway
import com.example.autop.policy.PolicyEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** AgentSession 组装。 */
@Module
@InstallIn(SingletonComponent::class)
public object AgentSessionModule {
    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，运行期端口逐个注入是职责所在
    public fun provideAgentSession(
        store: ThreadStore,
        llm: LlmGateway,
        prompts: PromptRepository,
        config: ConfigProvider,
        clock: Clock,
        tools: Set<@JvmSuppressWildcards Tool>,
        enrichers: Set<@JvmSuppressWildcards ToolResultEnricher>,
        contributors: Set<@JvmSuppressWildcards ContextContributor>,
        rules: Set<@JvmSuppressWildcards HistoryRenderRule>,
        compactors: Set<@JvmSuppressWildcards Compactor>,
        policy: PolicyEngine,
        platform: PlatformCapabilities,
        interaction: UserInteraction,
        audit: ToolAuditSink,
        logger: Logger,
        replayProbe: ReplayStateProbe,
        replayTargets: ReplayTargetResolver,
    ): AgentSession =
        DefaultAgentSession(
            services = RuntimeServices(store = store, llm = llm, prompts = prompts, config = config, clock = clock),
            extensions =
                RuntimeExtensions(
                    tools = tools,
                    enrichers = enrichers,
                    contributors = contributors,
                    rules = rules,
                    compactors = compactors,
                ),
            ports =
                RuntimePorts(
                    policy = policy,
                    platform = platform,
                    interaction = interaction,
                    audit = audit,
                    logger = logger,
                    replayProbe = replayProbe,
                    replayTargets = replayTargets,
                ),
        )
}
