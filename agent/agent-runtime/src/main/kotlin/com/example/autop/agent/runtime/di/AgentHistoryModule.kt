/**
 * 历史渲染规则与压缩策略装配（docs/tech/04 §3.4/§3.6）：三条内置规则与
 * 两种压缩策略的多绑定。public object + public @Provides 的理由见
 * AgentRuntimeModule 头注（N12）。
 */
package com.example.autop.agent.runtime.di

import com.example.autop.agent.Compactor
import com.example.autop.agent.HistoryRenderRule
import com.example.autop.agent.runtime.compaction.DeterministicCompactor
import com.example.autop.agent.runtime.compaction.HandoffCompactor
import com.example.autop.agent.runtime.rules.ImageRetentionRule
import com.example.autop.agent.runtime.rules.LongTextTruncateRule
import com.example.autop.agent.runtime.rules.ObservationDegradeRule
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.LlmGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** 历史渲染规则与压缩策略装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object AgentHistoryModule {
    // ---- 历史渲染规则（docs/tech/04 §3.4） ----

    @Provides
    @IntoSet
    public fun provideObservationDegradeRule(prompts: PromptRepository): HistoryRenderRule =
        ObservationDegradeRule(prompts)

    @Provides
    @IntoSet
    public fun provideImageRetentionRule(prompts: PromptRepository): HistoryRenderRule = ImageRetentionRule(prompts)

    @Provides
    @IntoSet
    public fun provideLongTextTruncateRule(prompts: PromptRepository): HistoryRenderRule = LongTextTruncateRule(prompts)

    // ---- 压缩策略（docs/tech/04 §3.6） ----

    @Provides
    @IntoSet
    public fun provideHandoffCompactor(
        llm: LlmGateway,
        prompts: PromptRepository,
    ): Compactor = HandoffCompactor(llm, prompts)

    @Provides
    @IntoSet
    public fun provideDeterministicCompactor(prompts: PromptRepository): Compactor = DeterministicCompactor(prompts)
}
