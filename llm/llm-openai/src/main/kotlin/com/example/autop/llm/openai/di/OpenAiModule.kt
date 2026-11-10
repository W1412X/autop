package com.example.autop.llm.openai.di

import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.SecretStore
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.EmbeddingProviderFactory
import com.example.autop.llm.ImageLoader
import com.example.autop.llm.LlmProviderFactory
import com.example.autop.llm.ModelDirectoryFactory
import com.example.autop.llm.ModelProfileStore
import com.example.autop.llm.http.RetryConfig
import com.example.autop.llm.openai.OpenAiContext
import com.example.autop.llm.openai.OpenAiEmbeddingContext
import com.example.autop.llm.openai.OpenAiEmbeddingProviderFactory
import com.example.autop.llm.openai.OpenAiModelDirectoryFactory
import com.example.autop.llm.openai.OpenAiProviderFactory
import com.example.autop.llm.openai.OpenAiQuirksDefaults
import com.example.autop.llm.openai.OpenAiRequestMapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * OpenAI 适配器的装配（docs/tech/03 §5.1、§7）：[LlmProviderFactory] 与
 * [EmbeddingProviderFactory] 经多绑定进扩展点集合。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 ConfigModule 的 mangling 教训，见 questions.md N12）。
 */
@Module
@InstallIn(SingletonComponent::class)
public object OpenAiModule {
    @Provides
    @Singleton
    public fun provideOpenAiRequestMapper(
        images: ImageLoader,
        prompts: PromptRepository,
    ): OpenAiRequestMapper = OpenAiRequestMapper(images, prompts)

    @Provides
    @Singleton
    public fun provideOpenAiQuirksDefaults(configProvider: ConfigProvider): OpenAiQuirksDefaults {
        val errors = configProvider.current()[LlmConfig.ERRORS]
        val image = configProvider.current()[LlmConfig.IMAGE]
        return OpenAiQuirksDefaults(
            contextExceededPatterns = errors.contextExceededPatterns.split('|').filter { it.isNotEmpty() },
            imageMaxEdge = image.maxEdge,
            contextExceededStatus = errors.contextExceededStatus,
        )
    }

    @Suppress("LongParameterList") // Hilt 装配：Context 是依赖打包类，逐项注入
    @Provides
    @Singleton
    public fun provideOpenAiContext(
        client: OkHttpClient,
        retryConfig: RetryConfig,
        secrets: SecretStore,
        mapper: OpenAiRequestMapper,
        quirksDefaults: OpenAiQuirksDefaults,
        dispatchers: DispatcherProvider,
        clock: Clock,
        logger: Logger,
    ): OpenAiContext =
        OpenAiContext(
            client = client,
            retryConfig = retryConfig,
            secrets = secrets,
            mapper = mapper,
            quirksDefaults = quirksDefaults,
            dispatchers = dispatchers,
            clock = clock,
            logger = logger,
        )

    @Provides
    @Singleton
    public fun provideOpenAiEmbeddingContext(
        client: OkHttpClient,
        retryConfig: RetryConfig,
        secrets: SecretStore,
        profiles: ModelProfileStore,
        quirksDefaults: OpenAiQuirksDefaults,
    ): OpenAiEmbeddingContext =
        OpenAiEmbeddingContext(
            client = client,
            secrets = secrets,
            profiles = profiles,
            quirksDefaults = quirksDefaults,
            retryStatus = retryConfig.retryStatus,
        )

    @Provides
    @IntoSet
    @Singleton
    public fun provideOpenAiProviderFactory(context: OpenAiContext): LlmProviderFactory = OpenAiProviderFactory(context)

    /** 远端模型列表扩展点（N164）。 */
    @Provides
    @IntoSet
    @Singleton
    public fun provideOpenAiModelDirectoryFactory(context: OpenAiContext): ModelDirectoryFactory =
        OpenAiModelDirectoryFactory(context)

    @Provides
    @IntoSet
    @Singleton
    public fun provideOpenAiEmbeddingFactory(context: OpenAiEmbeddingContext): EmbeddingProviderFactory =
        OpenAiEmbeddingProviderFactory(context)
}
