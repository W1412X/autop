package com.example.autop.core.config.impl.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.impl.DataStoreModelProfileStore
import com.example.autop.core.config.impl.ModelProfilesDoc
import com.example.autop.core.config.impl.ModelProfilesSerializer
import com.example.autop.core.platform.guardedReplaceHandler
import com.example.autop.llm.ModelProfileStore
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
 * `model_profiles.json` 的装配（docs/tech/03 §3）。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 ConfigModule 的 mangling 教训，见 questions.md N12）。
 */
@Module
@InstallIn(SingletonComponent::class)
public object ModelProfilesModule {
    @Provides
    @Singleton
    public fun provideModelProfilesDataStore(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): DataStore<ModelProfilesDoc> =
        DataStoreFactory.create(
            serializer = ModelProfilesSerializer(),
            corruptionHandler = guardedReplaceHandler { ModelProfilesSerializer().defaultValue },
            scope = CoroutineScope(SupervisorJob() + dispatchers.io + logger.exceptionHandler("ModelProfilesStore")),
        ) { File(context.filesDir, PROFILES_FILE) }

    @Provides
    @Singleton
    public fun provideModelProfileStore(dataStore: DataStore<ModelProfilesDoc>): ModelProfileStore =
        DataStoreModelProfileStore(dataStore)
}

private const val PROFILES_FILE = "model_profiles.json"
