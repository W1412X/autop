/**
 * agent 端口装配（docs/tech/07 §5、06 §5）：`UserInteraction`（ask_user 对话页
 * 回答）与 `ToolAuditSink`（工具审计）。
 */
package com.example.autop.task.impl.di

import com.example.autop.agent.ToolAuditSink
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.Logger
import com.example.autop.task.impl.ChatUserInteraction
import com.example.autop.task.impl.store.AuditStore
import com.example.autop.task.impl.store.RunStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 端口接口到实现的绑定。 */
@Module
@InstallIn(SingletonComponent::class)
public object TaskPortsModule {
    @Provides
    @Singleton
    public fun provideChatUserInteraction(
        runStore: RunStore,
        logger: Logger,
    ): ChatUserInteraction = ChatUserInteraction(runStore, logger)

    @Provides
    public fun provideUserInteraction(impl: ChatUserInteraction): UserInteraction = impl

    @Provides
    public fun provideToolAuditSink(impl: AuditStore): ToolAuditSink = impl
}
