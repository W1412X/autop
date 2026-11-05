package com.example.autop.llm.http.di

import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmRequestLog
import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelProbe
import com.example.autop.llm.http.DefaultEmbeddingGateway
import com.example.autop.llm.http.DefaultLlmGateway
import com.example.autop.llm.http.DefaultModelDirectory
import com.example.autop.llm.http.DefaultModelProbe
import com.example.autop.llm.http.HttpClientFactory
import com.example.autop.llm.http.HttpConfig
import com.example.autop.llm.http.InMemoryLlmRequestLog
import com.example.autop.llm.http.RetryConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * llm-http 通用件的装配（docs/tech/10 WP3）：OkHttpClient、RetryConfig，
 * 以及 [LlmGateway] / [ModelProbe] / [EmbeddingGateway] 默认实现的端口绑定。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 ConfigModule 的 mangling 教训，见 questions.md N12）。
 */
@Module
@InstallIn(SingletonComponent::class)
public object LlmHttpModule {
    @Provides
    @Singleton
    public fun provideOkHttpClient(configProvider: ConfigProvider): OkHttpClient {
        val http = configProvider.current()[LlmConfig.HTTP]
        return HttpClientFactory.create(
            HttpConfig(
                connectTimeoutMs = http.connectTimeoutMs,
                readTimeoutMs = http.readTimeoutMs,
                writeTimeoutMs = http.writeTimeoutMs,
            ),
        )
    }

    @Provides
    @Singleton
    public fun provideRetryConfig(configProvider: ConfigProvider): RetryConfig {
        val retry = configProvider.current()[LlmConfig.RETRY]
        return RetryConfig(
            maxAttempts = retry.maxAttempts,
            baseDelayMs = retry.baseDelayMs,
            maxDelayMs = retry.maxDelayMs,
            retryStatus = retry.retryStatus.toSet(),
        )
    }

    /** 默认实现经 @Inject 构造创建，这里只做端口绑定。 */
    @Provides
    @Singleton
    public fun provideLlmGateway(impl: DefaultLlmGateway): LlmGateway = impl

    /** 采样请求记录（调试页"查看上次请求"，docs/tech/08 §4.6）。 */
    @Provides
    @Singleton
    public fun provideLlmRequestLog(): LlmRequestLog = InMemoryLlmRequestLog()

    @Provides
    @Singleton
    public fun provideModelProbe(impl: DefaultModelProbe): ModelProbe = impl

    /** 远端模型列表（N164）：聚合实现按协议分发到适配器扩展点。 */
    @Provides
    @Singleton
    public fun provideModelDirectory(impl: DefaultModelDirectory): ModelDirectory = impl

    @Provides
    @Singleton
    public fun provideEmbeddingGateway(impl: DefaultEmbeddingGateway): EmbeddingGateway = impl
}
