/**
 * agent-runtime 的装配（docs/tech/04 §3.1/§3.9）：内置控制工具与
 * `ThreadRecall` 端口绑定。上下文贡献者见 AgentContextModule，
 * 历史渲染规则与压缩策略见 AgentHistoryModule。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 PolicyModule 的 mangling 教训，见 questions.md N12）。
 * `AgentSession` 绑定见 AgentSessionModule。
 */
package com.example.autop.agent.runtime.di

import com.example.autop.agent.ThreadRecall
import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolProbeService
import com.example.autop.agent.ToolResultEnricher
import com.example.autop.agent.runtime.recall.DefaultThreadRecall
import com.example.autop.agent.runtime.tools.ToolProber
import com.example.autop.agent.runtime.tools.control.AskUserTool
import com.example.autop.agent.runtime.tools.control.FinishTool
import com.example.autop.agent.runtime.tools.control.HistoryRecallTool
import com.example.autop.agent.runtime.tools.control.NoteTool
import com.example.autop.agent.runtime.tools.control.UpdatePlanTool
import com.example.autop.core.common.Clock
import com.example.autop.core.common.LogFiles
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** 内置控制工具与检索端口装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object AgentRuntimeModule {
    // ---- 内置控制工具（docs/tech/04 §3.9） ----

    @Provides
    @IntoSet
    public fun provideUpdatePlanTool(): Tool = UpdatePlanTool()

    @Provides
    @IntoSet
    public fun provideFinishTool(): Tool = FinishTool()

    @Provides
    @IntoSet
    public fun provideAskUserTool(prompts: PromptRepository): Tool = AskUserTool(prompts)

    @Provides
    @IntoSet
    public fun provideNoteTool(): Tool = NoteTool()

    @Provides
    @IntoSet
    public fun provideHistoryRecallTool(recall: ThreadRecall): Tool = HistoryRecallTool(recall)

    // ---- 历史检索（docs/tech/04 §2.5） ----

    /** 无检索器模块时的空多绑定兜底（索引器/检索器由 agent-store（WP6b）追加）。 */
    @Provides
    @ElementsIntoSet
    public fun provideNoRetrievers(): Set<@JvmSuppressWildcards ThreadRecallRetriever> = emptySet()

    /** 无工具结果后处理器时的空多绑定兜底。 */
    @Provides
    @ElementsIntoSet
    public fun provideNoEnrichers(): Set<@JvmSuppressWildcards ToolResultEnricher> = emptySet()

    @Provides
    @Singleton
    public fun provideThreadRecall(
        retrievers: Set<@JvmSuppressWildcards ThreadRecallRetriever>,
        config: ConfigProvider,
    ): ThreadRecall = DefaultThreadRecall(retrievers, config)

    // ---- 工具自测（N198）----

    /** 工具自测端口（悬浮面板选工具→任意页面执行→看输出，N198；参数说明取模板 meta，N207）。 */
    @Provides
    @Singleton
    public fun provideToolProber(
        tools: Set<@JvmSuppressWildcards Tool>,
        store: ThreadStore,
        config: ConfigProvider,
        clock: Clock,
        logFiles: LogFiles,
        prompts: PromptRepository,
    ): ToolProbeService =
        ToolProber(
            tools = tools.toList(),
            threadStore = store,
            config = config,
            clock = clock,
            logFiles = logFiles,
            prompts = prompts,
        )
}
