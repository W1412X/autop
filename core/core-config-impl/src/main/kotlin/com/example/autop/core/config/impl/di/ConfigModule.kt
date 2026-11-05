package com.example.autop.core.config.impl.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigKey
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.builtinConfigMigrations
import com.example.autop.core.config.impl.ConfigEditorImpl
import com.example.autop.core.config.impl.ConfigOverridesSerializer
import com.example.autop.core.config.impl.ConfigProviderImpl
import com.example.autop.core.config.impl.DataStoreOverridesStore
import com.example.autop.core.config.impl.OverridesStore
import com.example.autop.core.config.impl.loadDefaultConfigTree
import com.example.autop.core.config.impl.loadPolicyRules
import com.example.autop.core.config.impl.mergePolicyRules
import com.example.autop.core.config.impl.overridesCorruptionHandler
import com.example.autop.core.config.impl.readConfigAssetFiles
import com.example.autop.core.config.impl.readPolicyRulesAsset
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** 限定符：assets 默认值配置树（区别于用户覆盖文档）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class ConfigDefaults

/**
 * core-config 端口的装配（docs/tech/02 §2、§2.3）。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public；
 * Kotlin internal 方法名的 mangling 后缀与 KSP 生成代码不一致会导致 javac 找不到符号。
 */
@Module
@InstallIn(SingletonComponent::class)
public object ConfigModule {
    @Provides
    @Singleton
    @ConfigDefaults
    public fun provideDefaultTree(
        @ApplicationContext context: Context,
    ): JsonObject {
        val defaults = loadDefaultConfigTree(readConfigAssetFiles(context))
        // 内置策略规则文件挂到 policy.builtinRules（docs/tech/06 §6、questions.md N9）。
        val rules = loadPolicyRules(readPolicyRulesAsset(context))
        return mergePolicyRules(defaults, rules)
    }

    @Provides
    @Singleton
    public fun provideOverridesStore(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): OverridesStore {
        val dataStore: DataStore<JsonObject> =
            DataStoreFactory.create(
                serializer = ConfigOverridesSerializer(builtinConfigMigrations()),
                corruptionHandler = overridesCorruptionHandler(),
                scope =
                    CoroutineScope(
                        SupervisorJob() + dispatchers.io + logger.exceptionHandler("ConfigOverridesStore"),
                    ),
            ) { File(context.filesDir, OVERRIDES_FILE) }
        return DataStoreOverridesStore(dataStore)
    }

    @Provides
    @Singleton
    public fun provideConfigProvider(
        @ConfigDefaults defaults: JsonObject,
        keys: Set<@JvmSuppressWildcards ConfigKey<*>>,
        store: OverridesStore,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ): ConfigProvider = ConfigProviderImpl(defaults, keys.sortedBy { it.path }, store, dispatchers, logger)

    @Provides
    @Singleton
    public fun provideConfigEditor(
        @ConfigDefaults defaults: JsonObject,
        keys: Set<@JvmSuppressWildcards ConfigKey<*>>,
        store: OverridesStore,
        logger: Logger,
    ): ConfigEditor = ConfigEditorImpl(defaults, keys.sortedBy { it.path }, store, logger)
}

private const val OVERRIDES_FILE = "config_overrides.json"
