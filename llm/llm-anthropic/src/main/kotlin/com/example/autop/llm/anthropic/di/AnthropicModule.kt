package com.example.autop.llm.anthropic.di

import com.example.autop.core.common.SecretStore
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.ImageLoader
import com.example.autop.llm.LlmProviderFactory
import com.example.autop.llm.ModelDirectoryFactory
import com.example.autop.llm.anthropic.AnthropicContext
import com.example.autop.llm.anthropic.AnthropicModelDirectoryFactory
import com.example.autop.llm.anthropic.AnthropicProviderFactory
import com.example.autop.llm.anthropic.AnthropicQuirksDefaults
import com.example.autop.llm.anthropic.AnthropicRequestMapper
import com.example.autop.llm.http.RetryConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Anthropic 适配器的装配（docs/tech/03 §5.2）：[LlmProviderFactory] 经多绑定进扩展点集合。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 ConfigModule 的 mangling 教训，见 questions.md N12）。
 */
@Module
@InstallIn(SingletonComponent::class)
public object AnthropicModule {
    @Provides
    @Singleton
    public fun provideAnthropicRequestMapper(
        images: ImageLoader,
        prompts: PromptRepository,
    ): AnthropicRequestMapper = AnthropicRequestMapper(images, prompts)

    @Provides
    @Singleton
    public fun provideAnthropicQuirksDefaults(configProvider: ConfigProvider): AnthropicQuirksDefaults {
        val errors = configProvider.current()[LlmConfig.ERRORS]
        val image = configProvider.current()[LlmConfig.IMAGE]
        val anthropic = configProvider.current()[LlmConfig.ANTHROPIC]
        return AnthropicQuirksDefaults(
            contextExceededPatterns = errors.contextExceededPatterns.split('|').filter { it.isNotEmpty() },
            imageMaxEdge = image.maxEdge,
            apiVersion = anthropic.apiVersion,
        )
    }

    @Provides
    @Singleton
    public fun provideAnthropicContext(
        client: OkHttpClient,
        retryConfig: RetryConfig,
        secrets: SecretStore,
        mapper: AnthropicRequestMapper,
        quirksDefaults: AnthropicQuirksDefaults,
    ): AnthropicContext =
        AnthropicContext(
            client = client,
            retryConfig = retryConfig,
            secrets = secrets,
            mapper = mapper,
            quirksDefaults = quirksDefaults,
        )

    @Provides
    @IntoSet
    @Singleton
    public fun provideAnthropicProviderFactory(context: AnthropicContext): LlmProviderFactory =
        AnthropicProviderFactory(context)

    /** 远端模型列表扩展点（N164）。 */
    @Provides
    @IntoSet
    @Singleton
    public fun provideAnthropicModelDirectoryFactory(context: AnthropicContext): ModelDirectoryFactory =
        AnthropicModelDirectoryFactory(context)
}
