package com.example.autop.core.platform.di

import android.content.Context
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.platform.BitmapImageLoader
import com.example.autop.llm.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * [ImageLoader] 装配（docs/tech/03 §1：Bitmap 解码 + 长边限幅）。
 * 独立模块而非并入 PlatformModule：后者已达 TooManyFunctions 上限（questions.md N50）。
 */
@Module
@InstallIn(SingletonComponent::class)
public object ImageModule {
    @Provides
    @Singleton
    public fun provideImageLoader(
        dispatchers: DispatcherProvider,
        @ApplicationContext context: Context,
    ): ImageLoader = BitmapImageLoader(dispatchers, context.filesDir)
}
