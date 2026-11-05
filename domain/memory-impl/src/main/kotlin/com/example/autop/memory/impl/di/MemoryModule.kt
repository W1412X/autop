/**
 * 记忆装配（docs/tech/06）：`memory.db`、[MemoryStore] 实现、混合检索三通道、
 * L0 渲染器、记忆工具与上下文贡献者的多绑定。
 */
package com.example.autop.memory.impl.di

import android.content.Context
import androidx.room.Room
import com.example.autop.agent.ContextContributor
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolResultEnricher
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppLauncher
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.llm.LlmGateway
import com.example.autop.memory.ExperienceExtractor
import com.example.autop.memory.MemoryIndexMaintenance
import com.example.autop.memory.MemoryIndexRenderer
import com.example.autop.memory.MemoryRetriever
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.impl.EmbeddingIndexer
import com.example.autop.memory.impl.ExperienceExtractorImpl
import com.example.autop.memory.impl.HybridMemorySearch
import com.example.autop.memory.impl.MemoryIndexRendererImpl
import com.example.autop.memory.impl.MemorySeed
import com.example.autop.memory.impl.MemorySeeder
import com.example.autop.memory.impl.MemoryStoreImpl
import com.example.autop.memory.impl.ResourceMemorySeed
import com.example.autop.memory.impl.VectorCache
import com.example.autop.memory.impl.contributor.AppManualEnricher
import com.example.autop.memory.impl.contributor.MemoryIndexContributor
import com.example.autop.memory.impl.contributor.TriggerMemoryContributor
import com.example.autop.memory.impl.contributor.UserProfileContributor
import com.example.autop.memory.impl.db.MemoryDb
import com.example.autop.memory.impl.retriever.FtsRetriever
import com.example.autop.memory.impl.retriever.PathRetriever
import com.example.autop.memory.impl.retriever.VectorRetriever
import com.example.autop.memory.impl.tools.MemoryListTool
import com.example.autop.memory.impl.tools.MemoryReadTool
import com.example.autop.memory.impl.tools.MemorySearchTool
import com.example.autop.memory.impl.tools.MemoryWriteTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** 记忆装配。 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // 装配面：绑定清单随扩展点数量线性增长
public object MemoryModule {
    /** `memory.db`（docs/tech/06 §2）。 */
    @Provides
    @Singleton
    public fun provideMemoryDb(
        @ApplicationContext context: Context,
    ): MemoryDb = Room.databaseBuilder(context, MemoryDb::class.java, "memory.db").build()

    @Provides
    @Singleton
    public fun provideVectorCache(): VectorCache = VectorCache()

    @Provides
    @Singleton
    public fun provideMemorySeed(
        @ApplicationContext context: Context,
    ): MemorySeed = ResourceMemorySeed(context)

    @Provides
    @Singleton
    public fun provideMemorySeeder(
        db: MemoryDb,
        seed: MemorySeed,
        clock: Clock,
    ): MemorySeeder = MemorySeeder(db, seed, clock)

    @Provides
    @Singleton
    @Suppress("LongParameterList") // 装配函数：依赖都是存储的正交能力
    public fun provideMemoryStore(
        db: MemoryDb,
        prompts: PromptRepository,
        config: ConfigProvider,
        indexer: EmbeddingIndexer,
        seeder: MemorySeeder,
        dispatchers: DispatcherProvider,
        clock: Clock,
        logger: Logger,
    ): MemoryStore = MemoryStoreImpl(db, prompts, config, indexer, seeder, dispatchers, clock, logger)

    @Provides
    @Singleton
    public fun provideMemorySearch(
        db: MemoryDb,
        retrievers: Set<@JvmSuppressWildcards MemoryRetriever>,
        config: ConfigProvider,
        clock: Clock,
    ): MemorySearch = HybridMemorySearch(db, retrievers, config, clock)

    @Provides
    @Singleton
    public fun provideMemoryIndexRenderer(
        db: MemoryDb,
        prompts: PromptRepository,
        config: ConfigProvider,
    ): MemoryIndexRenderer = MemoryIndexRendererImpl(db, prompts, config)

    /** 记忆索引维护（docs/tech/10 WP10 设置页"重建记忆索引"）。 */
    @Provides
    @Singleton
    public fun provideMemoryIndexMaintenance(indexer: EmbeddingIndexer): MemoryIndexMaintenance = indexer

    /** 经验提炼（docs/design/agent-design.md §8，开关 `features.experienceExtraction`）。 */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // 装配函数：依赖都是提炼链路的正交能力
    public fun provideExperienceExtractor(
        store: MemoryStore,
        gateway: LlmGateway,
        prompts: PromptRepository,
        appLauncher: AppLauncher,
        config: ConfigProvider,
        logger: Logger,
    ): ExperienceExtractor = ExperienceExtractorImpl(store, gateway, prompts, appLauncher, config, logger)

    // ---- 检索通道（docs/tech/06 §2.6）----

    @Provides
    @IntoSet
    public fun providePathRetriever(db: MemoryDb): MemoryRetriever = PathRetriever(db)

    @Provides
    @IntoSet
    public fun provideFtsRetriever(db: MemoryDb): MemoryRetriever = FtsRetriever(db)

    @Provides
    @IntoSet
    public fun provideVectorRetriever(
        db: MemoryDb,
        gateway: EmbeddingGateway,
        cache: VectorCache,
        config: ConfigProvider,
    ): MemoryRetriever = VectorRetriever(db, gateway, cache, config)

    // ---- 记忆工具（docs/tech/06 §3）----

    @Provides
    @IntoSet
    public fun provideMemoryListTool(
        store: MemoryStore,
        config: ConfigProvider,
    ): Tool = MemoryListTool(store, config)

    @Provides
    @IntoSet
    public fun provideMemoryReadTool(store: MemoryStore): Tool = MemoryReadTool(store)

    @Provides
    @IntoSet
    public fun provideMemorySearchTool(
        search: MemorySearch,
        config: ConfigProvider,
    ): Tool = MemorySearchTool(search, config)

    @Provides
    @IntoSet
    public fun provideMemoryWriteTool(
        store: MemoryStore,
        appLauncher: AppLauncher,
    ): Tool = MemoryWriteTool(store, appLauncher)

    // ---- 上下文贡献者与 enricher（docs/tech/06 §3）----

    @Provides
    @IntoSet
    public fun provideMemoryIndexContributor(
        renderer: MemoryIndexRenderer,
        prompts: PromptRepository,
        config: ConfigProvider,
    ): ContextContributor = MemoryIndexContributor(renderer, prompts, config)

    @Provides
    @IntoSet
    public fun provideUserProfileContributor(
        store: MemoryStore,
        prompts: PromptRepository,
        config: ConfigProvider,
    ): ContextContributor = UserProfileContributor(store, prompts, config)

    @Provides
    @IntoSet
    public fun provideTriggerMemoryContributor(
        search: MemorySearch,
        prompts: PromptRepository,
        config: ConfigProvider,
    ): ContextContributor = TriggerMemoryContributor(search, prompts, config)

    @Provides
    @IntoSet
    public fun provideAppManualEnricher(
        store: MemoryStore,
        prompts: PromptRepository,
        appLauncher: AppLauncher,
    ): ToolResultEnricher = AppManualEnricher(store, prompts, appLauncher)
}
