package com.example.autop.core.config.impl.di

import com.example.autop.core.config.AllConfigKeys
import com.example.autop.core.config.ConfigKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

/**
 * 全部 [ConfigKey] 的 DI 多绑定登记（docs/tech/02 §2.1 注“优先多绑定”，questions.md N8）。
 * 新模块的配置键在本集合中并入后即被校验与高级配置编辑识别。
 */
@Module
@InstallIn(SingletonComponent::class)
public object ConfigKeysModule {
    @Provides
    @ElementsIntoSet
    public fun provideConfigKeys(): Set<ConfigKey<*>> = AllConfigKeys.ALL.toSet()
}
