/**
 * AI 创建会话图片存储的 DI：临时目录为应用缓存下的 author_images。
 */
package com.example.autop.core.platform.di

import android.content.Context
import com.example.autop.core.common.AuthorImageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object AuthorImageModule {
    @Provides
    @Singleton
    public fun provideAuthorImageStore(
        @ApplicationContext context: Context,
    ): AuthorImageStore = AuthorImageStore(File(context.filesDir, "author_images"))
}
