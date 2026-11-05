package com.example.autop.core.prompt.impl.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.prompt.PromptOverrideEditor
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.impl.DataStorePromptOverridesStore
import com.example.autop.core.prompt.impl.PromptOverrideEditorImpl
import com.example.autop.core.prompt.impl.PromptOverridesDoc
import com.example.autop.core.prompt.impl.PromptOverridesSerializer
import com.example.autop.core.prompt.impl.PromptOverridesStore
import com.example.autop.core.prompt.impl.PromptRepositoryImpl
import com.example.autop.core.prompt.impl.loadBuiltinTemplates
import com.example.autop.core.prompt.impl.promptOverridesCorruptionHandler
import com.example.autop.core.prompt.impl.readPromptAssetFiles
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * core-prompt 端口的装配（docs/tech/02 §3）。
 *
 * public 装配规避 KSP mangling（N12）；内置模板启动时全量加载校验。
 */
@Module
@InstallIn(SingletonComponent::class)
public object PromptModule {
    @Provides
    @Singleton
    public fun provideBuiltinTemplates(
        @ApplicationContext context: Context,
    ): Map<String, PromptTemplate> = loadBuiltinTemplates(readPromptAssetFiles(context))

    @Provides
    @Singleton
    public fun providePromptOverridesStore(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): PromptOverridesStore {
        val dataStore: DataStore<PromptOverridesDoc> =
            DataStoreFactory.create(
                serializer = PromptOverridesSerializer(),
                corruptionHandler = promptOverridesCorruptionHandler(),
                scope =
                    CoroutineScope(
                        SupervisorJob() + dispatchers.io + logger.exceptionHandler("PromptOverridesStore"),
                    ),
            ) { File(context.filesDir, OVERRIDES_FILE) }
        return DataStorePromptOverridesStore(dataStore)
    }

    @Provides
    @Singleton
    public fun providePromptRepository(
        builtins: Map<String, PromptTemplate>,
        overrides: PromptOverridesStore,
    ): PromptRepository = PromptRepositoryImpl(builtins, overrides)

    @Provides
    @Singleton
    public fun providePromptOverrideEditor(
        builtins: Map<String, PromptTemplate>,
        overrides: PromptOverridesStore,
    ): PromptOverrideEditor = PromptOverrideEditorImpl(builtins, overrides)
}

private const val OVERRIDES_FILE = "prompt_overrides.json"
