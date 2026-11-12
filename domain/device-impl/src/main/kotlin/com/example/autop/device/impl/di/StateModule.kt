/**
 * 运行状态来源装配（agent-harness-overhaul WP3.5.7）：device-impl 的四个来源
 * 注册为 [RuntimeStateSource] 多绑定。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.RuntimeStateSource
import com.example.autop.device.impl.state.EnvironmentStateSource
import com.example.autop.device.impl.state.InputStateSource
import com.example.autop.device.impl.state.NavigationStateSource
import com.example.autop.device.impl.state.ObserveStateSource
import com.example.autop.device.impl.state.PermissionStateSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** 运行状态来源装配。 */
@Module
@InstallIn(SingletonComponent::class)
public object StateModule {
    @Provides
    @IntoSet
    public fun provideEnvironmentStateSource(source: EnvironmentStateSource): RuntimeStateSource = source

    @Provides
    @IntoSet
    public fun provideInputStateSource(source: InputStateSource): RuntimeStateSource = source

    @Provides
    @IntoSet
    public fun provideObserveStateSource(source: ObserveStateSource): RuntimeStateSource = source

    @Provides
    @IntoSet
    public fun provideNavigationStateSource(source: NavigationStateSource): RuntimeStateSource = source

    @Provides
    @IntoSet
    public fun providePermissionStateSource(source: PermissionStateSource): RuntimeStateSource = source
}
