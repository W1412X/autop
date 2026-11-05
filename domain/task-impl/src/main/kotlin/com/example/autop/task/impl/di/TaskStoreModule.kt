/**
 * `tasks.db` 与存储装配（docs/tech/07 §3）：数据库、Run 记录与工具审计。
 */
package com.example.autop.task.impl.di

import android.content.Context
import androidx.room.Room
import com.example.autop.core.common.Clock
import com.example.autop.task.AuditLogReader
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.store.AuditLogReaderImpl
import com.example.autop.task.impl.store.AuditStore
import com.example.autop.task.impl.store.RunStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 任务域存储装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object TaskStoreModule {
    /** `tasks.db`（docs/tech/07 §3）。 */
    @Provides
    @Singleton
    public fun provideTasksDb(
        @ApplicationContext context: Context,
    ): TasksDb = Room.databaseBuilder(context, TasksDb::class.java, "tasks.db").build()

    @Provides
    @Singleton
    public fun provideRunStore(db: TasksDb): RunStore = RunStore(db)

    @Provides
    @Singleton
    public fun provideAuditStore(
        db: TasksDb,
        clock: Clock,
    ): AuditStore = AuditStore(db, clock)

    @Provides
    @Singleton
    public fun provideAuditLogReader(db: TasksDb): AuditLogReaderImpl = AuditLogReaderImpl(db)

    /** 审计日志分页读取（WP11"设置 → 高级 → 审计日志”页）。 */
    @Provides
    @Singleton
    public fun provideAuditLogReaderPort(impl: AuditLogReaderImpl): AuditLogReader = impl
}
