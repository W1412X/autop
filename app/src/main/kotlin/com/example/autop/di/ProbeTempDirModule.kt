package com.example.autop.di

import android.content.Context
import com.example.autop.llm.http.di.ProbeTempDir
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** 测试连接内置图片写入 cacheDir（`java.io.tmpdir` 在 Android 上不可靠，见 questions.md N36）。 */
@Module
@InstallIn(SingletonComponent::class)
object ProbeTempDirModule {
    @Provides
    @Singleton
    @ProbeTempDir
    fun provideProbeTempDir(
        @ApplicationContext context: Context,
    ): File = context.cacheDir
}
