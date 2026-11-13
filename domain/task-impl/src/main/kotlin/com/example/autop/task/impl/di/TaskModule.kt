/**
 * 调度与运行装配（docs/tech/07 §4.3/§4.5）：RunQueue、应用级协程作用域、
 * AgentHost 拉起端口与 RunCoordinator（`RunService`）。
 * 存储见 TaskStoreModule，agent 端口见 TaskPortsModule。
 */
package com.example.autop.task.impl.di

import android.content.Context
import com.example.autop.agent.AgentSession
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ScreenLease
import com.example.autop.device.TouchShield
import com.example.autop.device.UserActivityMonitor
import com.example.autop.flow.FlowProgressFeed
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowValidationService
import com.example.autop.llm.LlmGateway
import com.example.autop.memory.ExperienceExtractor
import com.example.autop.task.AgentHostStatus
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.RunService
import com.example.autop.task.TaskRepository
import com.example.autop.task.core.RunQueue
import com.example.autop.task.impl.ChatTitleGeneratorImpl
import com.example.autop.task.impl.ChatUserInteraction
import com.example.autop.task.impl.InterruptedRunRecovery
import com.example.autop.task.impl.PendingRuns
import com.example.autop.task.impl.RunControl
import com.example.autop.task.impl.RunControlImpl
import com.example.autop.task.impl.RunCoordinator
import com.example.autop.task.impl.RunExecutor
import com.example.autop.task.impl.RunObserver
import com.example.autop.task.impl.RunRunner
import com.example.autop.task.impl.RunScheduler
import com.example.autop.task.impl.TouchShieldWatcher
import com.example.autop.task.impl.host.AgentHostStarter
import com.example.autop.task.impl.host.AgentHostStarterImpl
import com.example.autop.task.impl.host.AgentHostStatusImpl
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.reminder.LockReminder
import com.example.autop.task.impl.store.RunStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** 应用级协程作用域限定符（docs/tech/07 §4.5：承载 RunCoordinator）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class AppScope

/** 调度与运行装配。 */
@Suppress("TooManyFunctions") // DI 装配面的自然结果（调度链的各环节各一个 provider）
@Module
@InstallIn(SingletonComponent::class)
public object TaskModule {
    @Provides
    @Singleton
    public fun provideRunQueue(config: ConfigProvider): RunQueue =
        RunQueue(config.current()[TaskConfig.QUEUE].originWeights)

    /**
     * 应用级作用域（SupervisorJob + default 调度器，docs/tech/07 §4.5）。
     * 带统一协程异常兜底：协程内未捕获异常只记日志不杀进程（N162/N165）。
     */
    @Provides
    @Singleton
    @AppScope
    public fun provideAppScope(
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                dispatchers.default +
                logger.exceptionHandler("AppScope"),
        )

    @Provides
    @Singleton
    public fun provideAgentHostStarter(
        @ApplicationContext context: Context,
        logger: Logger,
    ): AgentHostStarter = AgentHostStarterImpl(context, logger)

    /** 对话标题生成（首条消息 → LLM 精炼，失败保留占位标题）。 */
    @Provides
    @Singleton
    public fun provideChatTitleGenerator(
        gateway: LlmGateway,
        prompts: PromptRepository,
        config: ConfigProvider,
        logger: Logger,
    ): ChatTitleGenerator = ChatTitleGeneratorImpl(gateway, prompts, config, logger)

    @Provides
    @Singleton
    public fun provideAgentHostStatusImpl(): AgentHostStatusImpl = AgentHostStatusImpl()

    /** AgentHost 前台服务运行状态（WP11 健康检查）。 */
    @Provides
    @Singleton
    public fun provideAgentHostStatus(impl: AgentHostStatusImpl): AgentHostStatus = impl

    @Provides
    @Singleton
    public fun provideRunObserver(
        runStore: RunStore,
        configProvider: ConfigProvider,
        clock: Clock,
        flowFeed: FlowProgressFeed,
    ): RunObserver = RunObserver(runStore, configProvider, clock, flowFeed)

    @Provides
    @Singleton
    public fun providePendingRuns(): PendingRuns = PendingRuns()

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideRunRunner(
        agentSession: AgentSession,
        flowSession: FlowSession,
        flowStore: FlowStore,
        flowFeed: FlowProgressFeed,
        runStore: RunStore,
        pending: PendingRuns,
        observer: RunObserver,
        touchShieldWatcher: TouchShieldWatcher,
        screenLease: ScreenLease,
        deviceState: DeviceStateProvider,
        appLauncher: AppLauncher,
        notifier: UserNotifierImpl,
        taskStore: TaskRepository,
        threadStore: ThreadStore,
        experience: ExperienceExtractor,
        configProvider: ConfigProvider,
        prompts: PromptRepository,
        clock: Clock,
        @AppScope scope: CoroutineScope,
        logger: Logger,
    ): RunRunner =
        RunRunner(
            agentSession = agentSession,
            flowSession = flowSession,
            flowStore = flowStore,
            flowFeed = flowFeed,
            runStore = runStore,
            pending = pending,
            observer = observer,
            touchShieldWatcher = touchShieldWatcher,
            screenLease = screenLease,
            deviceState = deviceState,
            appLauncher = appLauncher,
            notifier = notifier,
            taskStore = taskStore,
            threadStore = threadStore,
            experience = experience,
            configProvider = configProvider,
            prompts = prompts,
            clock = clock,
            scope = scope,
            logger = logger,
        )

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideRunScheduler(
        runStore: RunStore,
        queue: RunQueue,
        pending: PendingRuns,
        runner: RunRunner,
        screenLease: ScreenLease,
        accessibilityStatus: AccessibilityStatus,
        deviceState: DeviceStateProvider,
        userActivity: UserActivityMonitor,
        lockReminder: LockReminder,
        notifier: UserNotifierImpl,
        configProvider: ConfigProvider,
        threadStore: ThreadStore,
        prompts: PromptRepository,
        clock: Clock,
        @AppScope scope: CoroutineScope,
        logger: Logger,
    ): RunScheduler =
        RunScheduler(
            runStore = runStore,
            queue = queue,
            pending = pending,
            runner = runner,
            screenLease = screenLease,
            accessibilityStatus = accessibilityStatus,
            deviceState = deviceState,
            userActivity = userActivity,
            lockReminder = lockReminder,
            notifier = notifier,
            configProvider = configProvider,
            threadStore = threadStore,
            prompts = prompts,
            clock = clock,
            scope = scope,
            logger = logger,
        )

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideRunExecutor(
        agentSession: AgentSession,
        threadStore: ThreadStore,
        runStore: RunStore,
        pending: PendingRuns,
        scheduler: RunScheduler,
        titleGenerator: ChatTitleGenerator,
        ids: IdGenerator,
        configProvider: ConfigProvider,
        clock: Clock,
        logger: Logger,
        @AppScope scope: CoroutineScope,
    ): RunExecutor =
        RunExecutor(
            agentSession = agentSession,
            threadStore = threadStore,
            runStore = runStore,
            pending = pending,
            scheduler = scheduler,
            titleGenerator = titleGenerator,
            ids = ids,
            configProvider = configProvider,
            clock = clock,
            logger = logger,
            scope = scope,
        )

    @Provides
    @Singleton
    public fun provideRunControl(impl: RunControlImpl): RunControl = impl

    @Provides
    @Singleton
    public fun provideRunControlImpl(
        @ApplicationContext context: Context,
        scheduler: RunScheduler,
        observer: RunObserver,
        lockReminder: LockReminder,
        logger: Logger,
    ): RunControlImpl = RunControlImpl(context, scheduler, observer, lockReminder, logger)

    @Provides
    @Singleton
    public fun provideRunService(impl: RunCoordinator): RunService = impl

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配方法，端口逐个注入是职责所在
    public fun provideInterruptedRunRecovery(
        runStore: RunStore,
        threadStore: ThreadStore,
        prompts: PromptRepository,
        executor: RunExecutor,
        clock: Clock,
        logger: Logger,
    ): InterruptedRunRecovery =
        InterruptedRunRecovery(
            runStore = runStore,
            threadStore = threadStore,
            prompts = prompts,
            executor = executor,
            clock = clock,
            logger = logger,
        )

    @Provides
    @Singleton
    @Suppress("LongParameterList") // 端口装配面（DI 注入）的自然结果
    public fun provideRunCoordinator(
        executor: RunExecutor,
        observer: RunObserver,
        interaction: ChatUserInteraction,
        host: AgentHostStarter,
        control: RunControl,
        recovery: InterruptedRunRecovery,
        shield: TouchShield,
        logger: Logger,
        flowStore: FlowStore,
        flowValidation: FlowValidationService,
        runStore: RunStore,
        threadStore: ThreadStore,
    ): RunCoordinator =
        RunCoordinator(
            executor,
            observer,
            interaction,
            host,
            control,
            recovery,
            shield,
            logger,
            flowStore,
            flowValidation,
            runStore,
            threadStore,
        )
}
