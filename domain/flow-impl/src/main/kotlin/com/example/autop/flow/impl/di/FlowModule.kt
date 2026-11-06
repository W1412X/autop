/**
 * flow 领域装配（docs/design/flows.md §18）：flows.db、存储端口、服务端口、FlowSession 与智能体工具。
 */
package com.example.autop.flow.impl.di

import android.content.Context
import androidx.room.Room
import com.example.autop.agent.AgentSession
import com.example.autop.agent.ContextContributor
import com.example.autop.agent.RunLineage
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.ToolProbeService
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.SecretStore
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.NotificationFeed
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.EndpointTester
import com.example.autop.flow.FlowAuthor
import com.example.autop.flow.FlowBundleService
import com.example.autop.flow.FlowRunStore
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowStateStore
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.RecordingSession
import com.example.autop.flow.impl.author.AuthorDeps
import com.example.autop.flow.impl.author.FlowAuthorImpl
import com.example.autop.flow.impl.context.FlowDirectoryContributor
import com.example.autop.flow.impl.db.FLOWS_MIGRATION_1_2
import com.example.autop.flow.impl.db.FlowsDb
import com.example.autop.flow.impl.ports.ApiPortImpl
import com.example.autop.flow.impl.recording.RecordingSessionImpl
import com.example.autop.flow.impl.run.FlowSessionDeps
import com.example.autop.flow.impl.run.FlowSessionImpl
import com.example.autop.flow.impl.service.EndpointTesterImpl
import com.example.autop.flow.impl.service.FlowBundleServiceImpl
import com.example.autop.flow.impl.service.FlowCatalogBuilder
import com.example.autop.flow.impl.service.FlowValidationServiceImpl
import com.example.autop.flow.impl.store.RoomEndpointStore
import com.example.autop.flow.impl.store.RoomFlowRunStore
import com.example.autop.flow.impl.store.RoomFlowStateStore
import com.example.autop.flow.impl.store.RoomFlowStore
import com.example.autop.flow.impl.tools.ListFlowsTool
import com.example.autop.flow.impl.tools.RunFlowTool
import com.example.autop.flow.impl.tools.SaveAsFlowTool
import com.example.autop.flow.impl.tools.UpdateFlowTool
import com.example.autop.llm.LlmGateway
import com.example.autop.memory.MemoryStore
import com.example.autop.task.RunService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

/** flows.db 及其上的存储（内部类型不外露，对外只提供 flow-api 端口）。 */
public class FlowStorage internal constructor(
    internal val db: FlowsDb,
    internal val runs: RoomFlowRunStore,
    internal val flows: RoomFlowStore,
    internal val state: RoomFlowStateStore,
    internal val endpoints: RoomEndpointStore,
)

/** flow 领域装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object FlowModule {
    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配：存储依赖逐个注入
    public fun provideFlowStorage(
        @ApplicationContext context: Context,
        clock: Clock,
        configProvider: ConfigProvider,
        secrets: SecretStore,
        analyzer: Provider<FlowValidationService>,
    ): FlowStorage {
        val db =
            Room
                .databaseBuilder(
                    context,
                    FlowsDb::class.java,
                    "flows.db",
                ).addMigrations(FLOWS_MIGRATION_1_2)
                .build()
        val runs = RoomFlowRunStore(db, context.filesDir, configProvider)
        // needsScreen 分析要读取其他流程（子流程）与工具元数据，用延迟获取避免构造期循环。
        val flows = RoomFlowStore(db, runs, clock) { analyzer.get().needsScreen(it) }
        return FlowStorage(db, runs, flows, RoomFlowStateStore(db, clock), RoomEndpointStore(db, secrets))
    }

    @Provides
    @Singleton
    public fun provideFlowStore(storage: FlowStorage): FlowStore = storage.flows

    @Provides
    @Singleton
    public fun provideFlowRunStore(storage: FlowStorage): FlowRunStore = storage.runs

    @Provides
    @Singleton
    public fun provideFlowStateStore(storage: FlowStorage): FlowStateStore = storage.state

    @Provides
    @Singleton
    public fun provideEndpointStore(storage: FlowStorage): EndpointStore = storage.endpoints

    @Provides
    @Singleton
    public fun provideFlowValidationService(
        store: Provider<FlowStore>,
        endpoints: Provider<EndpointStore>,
        probe: Provider<ToolProbeService>,
    ): FlowValidationService =
        FlowValidationServiceImpl(FlowCatalogBuilder({ store.get() }, { endpoints.get() }, { probe.get() }))

    @Provides
    @Singleton
    public fun provideFlowBundleService(
        store: FlowStore,
        endpoints: EndpointStore,
        validation: FlowValidationService,
        ids: IdGenerator,
    ): FlowBundleService = FlowBundleServiceImpl(store, endpoints, validation, ids)

    @Provides
    @Singleton
    public fun provideEndpointTester(
        endpoints: EndpointStore,
        secrets: SecretStore,
        configProvider: ConfigProvider,
        dispatchers: DispatcherProvider,
        clock: Clock,
    ): EndpointTester =
        EndpointTesterImpl(ApiPortImpl(endpoints, secrets, configProvider, dispatchers, OkHttpClient()), clock)

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配：会话依赖逐个注入
    public fun provideFlowSession(
        agentSession: Provider<AgentSession>,
        threadStore: ThreadStore,
        invokerFactory: Provider<ToolInvokerFactory>,
        probe: Provider<ToolProbeService>,
        interaction: UserInteraction,
        lineage: RunLineage,
        gateway: LlmGateway,
        prompts: PromptRepository,
        configProvider: ConfigProvider,
        clock: Clock,
        ids: IdGenerator,
        logger: Logger,
        dispatchers: DispatcherProvider,
        secrets: SecretStore,
        storage: FlowStorage,
        feed: NotificationFeed,
        device: DeviceStateProvider,
        memory: MemoryStore,
    ): FlowSession =
        FlowSessionImpl(
            FlowSessionDeps(
                agentSession = { agentSession.get() },
                threadStore = threadStore,
                invokerFactory = { invokerFactory.get() },
                probe = { probe.get() },
                interaction = interaction,
                lineage = lineage,
                gateway = gateway,
                prompts = prompts,
                configProvider = configProvider,
                clock = clock,
                ids = ids,
                logger = logger,
                dispatchers = dispatchers,
                secrets = secrets,
                endpoints = storage.endpoints,
                flows = storage.flows,
                state = storage.state,
                runs = storage.runs,
                feed = feed,
                device = device,
                http = OkHttpClient(),
                memory = memory,
            ),
        )

    @Provides
    @Singleton
    @Suppress("LongParameterList") // DI 装配：创作依赖逐个注入
    public fun provideFlowAuthor(
        storage: FlowStorage,
        gateway: LlmGateway,
        prompts: PromptRepository,
        configProvider: ConfigProvider,
        validation: FlowValidationService,
        threadStore: ThreadStore,
        clock: Clock,
        ids: IdGenerator,
        invokerFactory: Provider<ToolInvokerFactory>,
        probe: Provider<ToolProbeService>,
        runService: Provider<RunService>,
    ): FlowAuthor =
        FlowAuthorImpl(
            AuthorDeps(
                db = storage.db,
                gateway = gateway,
                prompts = prompts,
                configProvider = configProvider,
                validation = validation,
                store = storage.flows,
                endpoints = storage.endpoints,
                threads = threadStore,
                clock = clock,
                ids = ids,
                invokerFactory = { invokerFactory.get() },
                probe = { probe.get() },
                runService = { runService.get() },
            ),
        )

    @Provides
    @IntoSet
    public fun provideRunFlowTool(tool: RunFlowTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideListFlowsTool(tool: ListFlowsTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideSaveAsFlowTool(tool: SaveAsFlowTool): Tool = tool

    @Provides
    @Singleton
    public fun provideRecordingSession(impl: RecordingSessionImpl): RecordingSession = impl

    @Provides
    @IntoSet
    public fun provideUpdateFlowTool(tool: UpdateFlowTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideFlowDirectoryContributor(contributor: FlowDirectoryContributor): ContextContributor = contributor
}
