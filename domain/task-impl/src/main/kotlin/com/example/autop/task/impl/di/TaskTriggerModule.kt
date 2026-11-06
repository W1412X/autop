/**
 * 触发源与任务存储装配（docs/tech/07 §1.4/§3/§4.1）：触发源多绑定、
 * 任务仓储、触发状态与闹钟调度。判定/中枢见 TaskDraftModule。
 */
package com.example.autop.task.impl.di

import android.content.Context
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.NotificationFeed
import com.example.autop.task.TaskRepository
import com.example.autop.task.TriggerSource
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.schedule.AlarmScheduler
import com.example.autop.task.impl.sources.AppForegroundSource
import com.example.autop.task.impl.sources.ChargingSource
import com.example.autop.task.impl.sources.NetworkSource
import com.example.autop.task.impl.sources.NotificationTriggerSource
import com.example.autop.task.impl.sources.ScreenUnlockSource
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.store.TaskStore
import com.example.autop.task.impl.store.TriggerStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** 触发源与任务存储装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object TaskTriggerModule {
    @Provides
    @IntoSet
    public fun provideNotificationSource(feed: NotificationFeed): TriggerSource = NotificationTriggerSource(feed)

    @Provides
    @IntoSet
    public fun provideAppForegroundSource(deviceState: DeviceStateProvider): TriggerSource =
        AppForegroundSource(deviceState)

    @Provides
    @IntoSet
    public fun provideChargingSource(deviceState: DeviceStateProvider): TriggerSource = ChargingSource(deviceState)

    @Provides
    @IntoSet
    public fun provideNetworkSource(deviceState: DeviceStateProvider): TriggerSource = NetworkSource(deviceState)

    @Provides
    @IntoSet
    public fun provideScreenUnlockSource(): TriggerSource = ScreenUnlockSource()

    @Provides
    @Singleton
    public fun provideTriggerStateStore(db: TasksDb): TriggerStateStore = TriggerStateStore(db)

    @Provides
    @Singleton
    public fun provideTaskStore(
        db: TasksDb,
        runStore: RunStore,
        configProvider: ConfigProvider,
        sources: Set<@JvmSuppressWildcards TriggerSource>,
        flowState: com.example.autop.flow.FlowStateStore,
    ): TaskStore =
        TaskStore(
            db = db,
            runStore = runStore,
            configProvider = configProvider,
            knownSources = { sources.map { it.id }.toSet() },
            onDeleted = {
                flowState.clearScope(
                    com.example.autop.flow.FlowStateStore
                        .taskScope(it.value),
                )
            },
        )

    @Provides
    @Singleton
    public fun provideTaskRepository(impl: TaskStore): TaskRepository = impl

    @Provides
    @Singleton
    public fun provideAlarmScheduler(
        @ApplicationContext context: Context,
        clock: Clock,
        logger: Logger,
    ): AlarmScheduler = AlarmScheduler(context, clock, logger)
}
