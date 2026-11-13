/**
 * 检索向量页 ViewModel（docs/tech/08 §4.5 `me/embedding`、docs/tech/03 §7）。
 *
 * 读写 `llm.embedding` 配置段（整段覆盖写入，避免逐字段覆盖互相覆盖）；
 * 更换模型后 `dimension` 重置为未知（N37）。重建进度（[ReindexUiState]）由
 * [MemoryIndexMaintenance] / [ThreadIndexMaintenance] 提供：配置变更后自动重建，
 * 也可手动触发（docs/tech/10 WP10"为历史对话重建向量索引"）。
 */
package com.example.autop.feature.settings.me.embedding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.agent.ThreadIndexMaintenance
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.LlmConfig
import com.example.autop.core.config.LlmEmbeddingSection
import com.example.autop.llm.ModelProfileStore
import com.example.autop.memory.MemoryIndexMaintenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 检索向量页 ViewModel。 */
@HiltViewModel
@Suppress("LongParameterList") // 7 个注入依赖（配置/编辑/档案/两路索引维护/调度/日志）均为必需
public class EmbeddingViewModel
    @Inject
    constructor(
        configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        store: ModelProfileStore,
        private val memoryIndex: MemoryIndexMaintenance,
        private val threadIndex: ThreadIndexMaintenance,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val reindex = MutableStateFlow(ReindexUiState())

        public val uiState: StateFlow<EmbeddingUiState> =
            combine(configProvider.observe(), store.profiles, reindex) { snapshot, profiles, reindex ->
                EmbeddingUiState(
                    section = snapshot[LlmConfig.EMBEDDING],
                    profiles = profiles,
                    reindex = reindex,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = EmbeddingUiState(),
            )

        public fun onAction(action: EmbeddingAction) {
            when (action) {
                is EmbeddingAction.SetProvider ->
                    write { section ->
                        if (action.id == PROVIDER_NONE || action.id == PROVIDER_LOCAL) {
                            section.copy(provider = action.id, profileId = null, model = null, dimension = null)
                        } else {
                            section.copy(provider = action.id, dimension = null)
                        }
                    }
                is EmbeddingAction.SetProfile -> write { it.copy(profileId = action.id, dimension = null) }
                is EmbeddingAction.SetModel ->
                    write {
                        it.copy(
                            model = action.value.ifBlank { null },
                            dimension = null,
                        )
                    }
                EmbeddingAction.ReindexMemory -> reindexMemory()
                EmbeddingAction.ReindexThreads -> reindexThreads()
            }
        }

        private fun write(transform: (LlmEmbeddingSection) -> LlmEmbeddingSection) {
            val next = transform(uiState.value.section)
            viewModelScope.launch(dispatchers.io) {
                val result =
                    configEditor
                        .setOverride(
                            LlmConfig.EMBEDDING.path,
                            AutopJson.default.encodeToJsonElement(LlmEmbeddingSection.serializer(), next),
                        ).onErr { error ->
                            logger.e(
                                tag = TAG,
                                event = "embedding_write_failed",
                                "path" to LlmConfig.EMBEDDING.path,
                                "code" to error.code,
                                error = error.cause,
                            )
                        }
                // 配置变化后自动重建两路索引（08 §4.5"更换后显示进度"）
                if (result is Outcome.Ok) {
                    reindexMemory()
                    reindexThreads()
                }
            }
        }

        /** 重建记忆索引（docs/tech/10 WP10）。 */
        private fun reindexMemory() {
            viewModelScope.launch(dispatchers.io) {
                runCatching {
                    memoryIndex.rebuildAll { done, total ->
                        reindex.update { it.copy(memory = ReindexProgress(done, total)) }
                    }
                }.onFailure { logger.w(tag = TAG, event = "memory_reindex_failed", error = it) }
                reindex.update { it.copy(memory = null) }
            }
        }

        /** 为历史对话重建向量索引（docs/tech/10 WP10）。 */
        private fun reindexThreads() {
            viewModelScope.launch(dispatchers.io) {
                runCatching {
                    threadIndex.rebuildAll { done, total ->
                        reindex.update { it.copy(threads = ReindexProgress(done, total)) }
                    }
                }.onFailure { logger.w(tag = TAG, event = "threads_reindex_failed", error = it) }
                reindex.update { it.copy(threads = null) }
            }
        }

        private companion object {
            const val TAG = "EmbeddingSettings"

            /** 未启用（docs/tech/03 §7 EmbeddingConfig.provider 的 `none`）。 */
            const val PROVIDER_NONE = "none"

            /** 端侧模型（ADR-0028）：不需要 profile，模型随应用分发。 */
            const val PROVIDER_LOCAL = "local"
        }
    }
