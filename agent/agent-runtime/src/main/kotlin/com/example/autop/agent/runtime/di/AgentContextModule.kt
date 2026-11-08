/**
 * 上下文贡献者装配（docs/tech/04 §3.10）：六个内置 contributor 的多绑定。
 * public object + public @Provides 的理由见 AgentRuntimeModule 头注（N12）。
 */
package com.example.autop.agent.runtime.di

import com.example.autop.agent.ContextContributor
import com.example.autop.agent.runtime.GoalContributor
import com.example.autop.agent.runtime.OutputContributor
import com.example.autop.agent.runtime.RoleContributor
import com.example.autop.agent.runtime.RunModeContributor
import com.example.autop.agent.runtime.SafetyContributor
import com.example.autop.agent.runtime.ToolGuideContributor
import com.example.autop.agent.runtime.TriggerContributor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** 上下文贡献者装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object AgentContextModule {
    @Provides
    @IntoSet
    public fun provideRoleContributor(prompts: PromptRepository): ContextContributor = RoleContributor(prompts)

    @Provides
    @IntoSet
    public fun provideSafetyContributor(prompts: PromptRepository): ContextContributor = SafetyContributor(prompts)

    @Provides
    @IntoSet
    public fun provideToolGuideContributor(prompts: PromptRepository): ContextContributor =
        ToolGuideContributor(prompts)

    /** 输出要求（语言 + 简洁，`ui.appearance.language` 驱动）。 */
    @Provides
    @IntoSet
    public fun provideOutputContributor(
        prompts: PromptRepository,
        configProvider: ConfigProvider,
    ): ContextContributor = OutputContributor(prompts, configProvider)

    @Provides
    @IntoSet
    public fun provideRunModeContributor(prompts: PromptRepository): ContextContributor = RunModeContributor(prompts)

    @Provides
    @IntoSet
    public fun provideTriggerContributor(prompts: PromptRepository): ContextContributor = TriggerContributor(prompts)

    @Provides
    @IntoSet
    public fun provideGoalContributor(prompts: PromptRepository): ContextContributor = GoalContributor(prompts)
}
