/**
 * [LlmGateway] 默认实现（docs/tech/10 WP3）：注入全部 [LlmProviderFactory]（扩展点多绑定），
 * 按 `profile.protocol` 匹配 [LlmProviderFactory.protocolId] 并缓存 provider 实例；
 * 模型配置（[ModelProfileStore]）变化时缓存失效重建。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.exceptionHandler
import com.example.autop.llm.LlmErrorCodes
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmProviderFactory
import com.example.autop.llm.LlmRequestLog
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

public class DefaultLlmGateway
    @Inject
    constructor(
        private val factories: Set<@JvmSuppressWildcards LlmProviderFactory>,
        private val store: ModelProfileStore,
        private val requestLog: LlmRequestLog,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) : LlmGateway {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.io + logger.exceptionHandler("LlmGateway"))
        private val lock = Any()
        private var state: State? = null
        private val cache = mutableMapOf<String, LlmProvider>()

        init {
            scope.launch {
                combine(store.profiles, store.defaultProfileId) { list, defaultId -> State.of(list, defaultId) }
                    .collect { newState ->
                        synchronized(lock) {
                            state = newState
                            cache.clear()
                        }
                    }
            }
        }

        override fun forDefault(): Outcome<LlmProvider> {
            val current = ensureLoaded()
            val profileId =
                current.defaultId
                    ?: return Outcome.Err(AppError(code = LlmErrorCodes.NOT_CONFIGURED, message = "no default model"))
            return providerFor(profileId, current)
        }

        override fun forProfile(profileId: String): Outcome<LlmProvider> = providerFor(profileId, ensureLoaded())

        private fun providerFor(
            profileId: String,
            current: State,
        ): Outcome<LlmProvider> {
            val profile = current.profiles[profileId]
            return if (profile == null) {
                Outcome.Err(
                    AppError(code = LlmErrorCodes.NOT_CONFIGURED, message = "model profile not found: $profileId"),
                )
            } else {
                cachedOrCreate(profile)
            }
        }

        private fun cachedOrCreate(profile: ModelProfile): Outcome<LlmProvider> =
            synchronized(lock) {
                val cached = cache[profile.id]
                val factory = factories.firstOrNull { it.protocolId == profile.protocol }
                when {
                    cached != null -> Outcome.Ok(cached)
                    factory == null ->
                        Outcome.Err(
                            AppError(
                                code = LlmErrorCodes.NOT_CONFIGURED,
                                message = "no provider factory for protocol: ${profile.protocol}",
                            ),
                        )
                    else -> {
                        // 装饰记录请求（docs/tech/08 §4.6 调试页“查看上次请求”）。
                        val provider = RecordingLlmProvider(factory.create(profile), requestLog)
                        cache[profile.id] = provider
                        Outcome.Ok(provider)
                    }
                }
            }

        /**
         * 非 suspend 查询的确定性保证：首次访问同步读一次配置快照（questions.md N34），
         * 之后由后台收集保持最新（配置变化同时清空缓存）。
         */
        private fun ensureLoaded(): State =
            synchronized(lock) {
                state ?: runBlocking {
                    combine(store.profiles, store.defaultProfileId) { list, defaultId ->
                        State.of(list, defaultId)
                    }.first()
                }.also { state = it }
            }

        private class State(
            val profiles: Map<String, ModelProfile>,
            val defaultId: String?,
        ) {
            companion object {
                fun of(
                    list: List<ModelProfile>,
                    defaultId: String?,
                ): State = State(list.associateBy { it.id }, defaultId)
            }
        }
    }
