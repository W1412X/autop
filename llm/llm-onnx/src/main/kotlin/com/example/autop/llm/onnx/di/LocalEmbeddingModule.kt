/**
 * 端侧 embedding 的装配：工厂经多绑定进入 `EmbeddingProviderFactory` 扩展点集合。
 */
package com.example.autop.llm.onnx.di

import android.content.Context
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.llm.EmbeddingProviderFactory
import com.example.autop.llm.onnx.LocalEmbeddingProviderFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
public object LocalEmbeddingModule {
    @Provides
    @IntoSet
    public fun provideLocalEmbeddingFactory(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): EmbeddingProviderFactory = LocalEmbeddingProviderFactory(context.assets, dispatchers, logger)
}
