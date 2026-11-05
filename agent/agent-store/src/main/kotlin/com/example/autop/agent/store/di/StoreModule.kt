/**
 * agent-store 装配（docs/tech/04 §4/§2.5）：`threads.db`、[ThreadStore] 实现、
 * FTS 与向量的索引器/检索器多绑定。
 */
package com.example.autop.agent.store.di

import android.content.Context
import androidx.room.Room
import com.example.autop.agent.ThreadEventIndexer
import com.example.autop.agent.ThreadIndexMaintenance
import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.store.ThreadFiles
import com.example.autop.agent.store.ThreadIndexMaintenanceImpl
import com.example.autop.agent.store.ThreadMappers
import com.example.autop.agent.store.ThreadStoreImpl
import com.example.autop.agent.store.db.ThreadsDb
import com.example.autop.agent.store.recall.FtsIndexer
import com.example.autop.agent.store.recall.FtsRetriever
import com.example.autop.agent.store.recall.IndexQueue
import com.example.autop.agent.store.recall.SnippetReader
import com.example.autop.agent.store.recall.VectorIndexer
import com.example.autop.agent.store.recall.VectorRetriever
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.llm.EmbeddingGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** 线程存储装配。 */
@Suppress("TooManyFunctions") // Hilt 装配模块：各 provider 一一对应组件，拆分无收益
@Module
@InstallIn(SingletonComponent::class)
public object StoreModule {
    /** `threads.db`（docs/tech/04 §4）。 */
    @Provides
    @Singleton
    public fun provideThreadsDb(
        @ApplicationContext context: Context,
    ): ThreadsDb = Room.databaseBuilder(context, ThreadsDb::class.java, "threads.db").build()

    @Provides
    @Singleton
    public fun provideThreadFiles(
        @ApplicationContext context: Context,
    ): ThreadFiles = ThreadFiles(context.filesDir)

    @Provides
    @Singleton
    public fun provideSnippetReader(
        db: ThreadsDb,
        files: ThreadFiles,
        config: ConfigProvider,
    ): SnippetReader = SnippetReader(db.eventDao(), files, config)

    @Provides
    @Singleton
    public fun provideIndexQueue(
        indexers: Set<@JvmSuppressWildcards ThreadEventIndexer>,
        config: ConfigProvider,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): IndexQueue =
        IndexQueue(
            indexers = indexers,
            capacity = config.current()[AgentConfig.RECALL].indexQueueCapacity,
            config = config,
            scope = CoroutineScope(SupervisorJob() + dispatchers.default + logger.exceptionHandler("IndexQueue")),
            logger = logger,
        )

    @Provides
    @Singleton
    public fun provideThreadMappers(
        files: ThreadFiles,
        config: ConfigProvider,
    ): ThreadMappers = ThreadMappers(files, config)

    @Provides
    @Singleton
    public fun provideThreadStore(
        db: ThreadsDb,
        mappers: ThreadMappers,
        indexQueue: IndexQueue,
        clock: Clock,
        ids: IdGenerator,
    ): ThreadStore = ThreadStoreImpl(db, mappers, indexQueue, clock, ids)

    /** 对话索引维护（docs/tech/10 WP10"为历史对话重建向量索引"）。 */
    @Provides
    @Singleton
    public fun provideThreadIndexMaintenance(
        store: ThreadStore,
        indexQueue: IndexQueue,
    ): ThreadIndexMaintenance = ThreadIndexMaintenanceImpl(store, indexQueue)

    // ---- 检索索引器与通道（docs/tech/04 §2.5） ----

    @Provides
    @IntoSet
    public fun provideFtsIndexer(
        db: ThreadsDb,
        snippets: SnippetReader,
    ): ThreadEventIndexer = FtsIndexer(db.ftsDao(), snippets)

    @Provides
    @IntoSet
    public fun provideVectorIndexer(
        db: ThreadsDb,
        embedding: EmbeddingGateway,
        snippets: SnippetReader,
        config: ConfigProvider,
        clock: Clock,
    ): ThreadEventIndexer = VectorIndexer(db.vectorDao(), embedding, snippets, config, clock)

    @Provides
    @IntoSet
    public fun provideFtsRetriever(
        db: ThreadsDb,
        snippets: SnippetReader,
    ): ThreadRecallRetriever = FtsRetriever(db.ftsDao(), snippets)

    @Provides
    @IntoSet
    public fun provideVectorRetriever(
        db: ThreadsDb,
        embedding: EmbeddingGateway,
        snippets: SnippetReader,
    ): ThreadRecallRetriever = VectorRetriever(db.vectorDao(), embedding, snippets)
}
