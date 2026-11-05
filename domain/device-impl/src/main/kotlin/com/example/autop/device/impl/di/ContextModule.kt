/**
 * 设备侧上下文贡献者的多绑定登记（docs/tech/05 §4）。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.ContextContributor
import com.example.autop.device.impl.EnvironmentContributor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** 环境快照上下文（本地时间、电量、网络、前台应用等）。 */
@Module
@InstallIn(SingletonComponent::class)
public object ContextModule {
    @Provides
    @IntoSet
    public fun provideEnvironmentContributor(contributor: EnvironmentContributor): ContextContributor = contributor
}
