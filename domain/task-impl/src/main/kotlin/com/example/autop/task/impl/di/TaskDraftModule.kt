/**
 * 判定、草稿与触发中枢装配（docs/tech/07 §4.2/§6）：LLM 条件判定、
 * 任务草稿生成、TriggerHub 与试运行模拟器。
 */
package com.example.autop.task.impl.di

import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.NotificationFeed
import com.example.autop.flow.FlowStore
import com.example.autop.llm.LlmGateway
import com.example.autop.task.ConditionEvaluator
import com.example.autop.task.TaskAuthor
import com.example.autop.task.TaskDraftGenerator
import com.example.autop.task.TaskRepository
import com.example.autop.task.TriggerSimulator
import com.example.autop.task.TriggerSource
import com.example.autop.task.impl.RunCoordinator
import com.example.autop.task.impl.author.TaskAuthorDeps
import com.example.autop.task.impl.author.TaskAuthorImpl
import com.example.autop.task.impl.draft.LlmConditionEvaluator
import com.example.autop.task.impl.draft.LlmTaskDraftGenerator
import com.example.autop.task.impl.schedule.TriggerHub
import com.example.autop.task.impl.schedule.TriggerRunner
import com.example.autop.task.impl.schedule.TriggerSimulatorImpl
import com.example.autop.task.impl.store.TaskStore
import com.example.autop.task.impl.store.TriggerStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Provider
import javax.inject.Singleton

/** 判定、草稿与触发中枢装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object TaskDraftModule {
    @Provides
    @Singleton
    public fun provideLlmConditionEvaluator(
        gateway: LlmGateway,
        prompts: PromptRepository,
        logger: Logger,
    ): LlmConditionEvaluator = LlmConditionEvaluator(gateway, prompts, logger)

    @Provides
    @IntoSet
    public fun provideConditionEvaluator(impl: LlmConditionEvaluator): ConditionEvaluator = impl

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideTaskDraftGenerator(
        gateway: LlmGateway,
        prompts: PromptRepository,
        configProvider: ConfigProvider,
        sources: Set<@JvmSuppressWildcards TriggerSource>,
        ids: IdGenerator,
        clock: Clock,
        logger: Logger,
    ): LlmTaskDraftGenerator =
        LlmTaskDraftGenerator(
            gateway = gateway,
            prompts = prompts,
            configProvider = configProvider,
            sources = { sources.toList() },
            ids = ids,
            clock = clock,
            logger = logger,
        )

    @Provides
    @Singleton
    public fun provideTaskDraftGeneratorPort(impl: LlmTaskDraftGenerator): TaskDraftGenerator = impl

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideTaskAuthor(
        gateway: LlmGateway,
        prompts: PromptRepository,
        configProvider: ConfigProvider,
        tasks: TaskRepository,
        flows: FlowStore,
        sources: Set<@JvmSuppressWildcards TriggerSource>,
        threads: ThreadStore,
        clock: Clock,
        ids: IdGenerator,
        invokerFactory: Provider<ToolInvokerFactory>,
    ): TaskAuthor =
        TaskAuthorImpl(
            TaskAuthorDeps(
                gateway = gateway,
                prompts = prompts,
                configProvider = configProvider,
                tasks = tasks,
                flows = flows,
                sources = sources,
                threads = threads,
                clock = clock,
                ids = ids,
                invokerFactory = { invokerFactory.get() },
            ),
        )

    @Provides
    @Singleton
    public fun provideTriggerRunner(impl: RunCoordinator): TriggerRunner = impl

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideTriggerHub(
        taskStore: TaskStore,
        stateStore: TriggerStateStore,
        sources: Set<@JvmSuppressWildcards TriggerSource>,
        evaluators: Set<@JvmSuppressWildcards ConditionEvaluator>,
        runner: TriggerRunner,
        configProvider: ConfigProvider,
        clock: Clock,
        @AppScope scope: CoroutineScope,
        logger: Logger,
    ): TriggerHub =
        TriggerHub(
            taskStore = taskStore,
            stateStore = stateStore,
            sources = sources.toList(),
            evaluators = evaluators.toList(),
            runner = runner,
            configProvider = configProvider,
            clock = clock,
            scope = scope,
            logger = logger,
        )

    @Provides
    @Singleton
    public fun provideTriggerSimulator(
        feed: NotificationFeed,
        hub: TriggerHub,
    ): TriggerSimulatorImpl = TriggerSimulatorImpl(feed, hub)

    @Provides
    @Singleton
    public fun provideTriggerSimulatorPort(impl: TriggerSimulatorImpl): TriggerSimulator = impl
}
