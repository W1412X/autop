/**
 * 通知、提醒与审计装配（docs/tech/07 §7/§8）：UserNotifier、锁屏提醒、
 * notify_user 工具与审计清理。
 */
package com.example.autop.task.impl.di

import android.content.Context
import com.example.autop.agent.Tool
import com.example.autop.core.common.Clock
import com.example.autop.core.config.ConfigProvider
import com.example.autop.task.UserNotifier
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.reminder.LockReminder
import com.example.autop.task.impl.store.AuditCleaner
import com.example.autop.task.impl.tools.NotifyUserTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** 通知、提醒与审计装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object TaskAgentModule {
    @Provides
    @Singleton
    public fun provideLockReminder(
        @ApplicationContext context: Context,
        configProvider: ConfigProvider,
        clock: Clock,
    ): LockReminder = LockReminder(context, configProvider, clock)

    @Provides
    @Singleton
    public fun provideUserNotifier(
        @ApplicationContext context: Context,
        configProvider: ConfigProvider,
    ): UserNotifierImpl = UserNotifierImpl(context, configProvider)

    @Provides
    @Singleton
    public fun provideUserNotifierPort(impl: UserNotifierImpl): UserNotifier = impl

    @Provides
    @Singleton
    public fun provideAuditCleaner(
        @ApplicationContext context: Context,
        db: TasksDb,
        configProvider: ConfigProvider,
        clock: Clock,
    ): AuditCleaner = AuditCleaner(context, db, configProvider, clock)

    @Provides
    @IntoSet
    public fun provideNotifyUserTool(tool: NotifyUserTool): Tool = tool
}
