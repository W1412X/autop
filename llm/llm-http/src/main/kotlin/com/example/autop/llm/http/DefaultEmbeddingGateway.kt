/**
 * [EmbeddingGateway] 默认实现（docs/tech/03 §7）：按配置 `llm.embedding` 返回当前 provider，
 * 未配置（provider=`none`）返回 null；配置变化时创建新实例
 * （[com.example.autop.llm.EmbeddingProvider.modelId] 随之变化，使用方据此重建索引）。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.config.LlmEmbeddingSection
import com.example.autop.llm.EmbeddingConfig
import com.example.autop.llm.EmbeddingGateway
import com.example.autop.llm.EmbeddingProvider
import com.example.autop.llm.EmbeddingProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

public class DefaultEmbeddingGateway
    @Inject
    constructor(
        private val factories: Set<@JvmSuppressWildcards EmbeddingProviderFactory>,
        private val configProvider: ConfigProvider,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) : EmbeddingGateway {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.io + logger.exceptionHandler("EmbeddingGateway"))
        private val state = MutableStateFlow<EmbeddingProvider?>(null)
        private val initialized = AtomicBoolean(false)

        init {
            scope.launch {
                configProvider
                    .observe()
                    .map { it[LlmConfig.EMBEDDING] }
                    .distinctUntilChanged()
                    .collect { section ->
                        state.value = create(section)
                        // 首个发射即视为就绪，[current] 不再重复创建（同配置下的幂等覆盖无意义）。
                        initialized.set(true)
                    }
            }
        }

        override fun current(): EmbeddingProvider? {
            if (initialized.compareAndSet(false, true)) {
                state.value = create(configProvider.current()[LlmConfig.EMBEDDING])
            }
            return state.value
        }

        override val changes: Flow<EmbeddingProvider?> = state

        private fun create(section: LlmEmbeddingSection): EmbeddingProvider? {
            val factory = factories.firstOrNull { it.id == section.provider }
            return if (section.provider == PROVIDER_NONE || factory == null) {
                null
            } else {
                factory.create(
                    EmbeddingConfig(
                        provider = section.provider,
                        profileId = section.profileId,
                        model = section.model,
                        dimension = section.dimension,
                        batchSize = section.batchSize,
                    ),
                )
            }
        }

        private companion object {
            const val PROVIDER_NONE = "none"
        }
    }
