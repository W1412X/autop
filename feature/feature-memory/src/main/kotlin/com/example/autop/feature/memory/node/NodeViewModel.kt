/**
 * 条目编辑页 ViewModel（docs/tech/08 §4.4）：编辑保存、版本历史与恢复、
 * agent 写入的确认 / 撤销、删除与移动。
 */
package com.example.autop.feature.memory.node

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.feature.memory.MEMORY_PATH_ARG
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 条目编辑页 ViewModel。 */
@Suppress("TooManyFunctions") // 每个动作一个职责单一的私有处理函数，函数数即动作数
@HiltViewModel
public class NodeViewModel
    @Inject
    constructor(
        private val store: MemoryStore,
        private val configProvider: ConfigProvider,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val path: MemoryPath =
            MemoryPath.parse(savedState.get<String>(MEMORY_PATH_ARG) ?: "/").getOrNull() ?: MemoryPath.ROOT

        private val _uiState =
            MutableStateFlow(
                NodeUiState(
                    path = path.value,
                    summaryMaxChars = configProvider.current()[MemoryConfig.SUMMARY].maxChars,
                ),
            )
        public val uiState: StateFlow<NodeUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch { load() }
        }

        /** 分派用户动作。 */
        @Suppress("CyclomaticComplexMethod") // 纯动作分派 when，分支数即动作数，无嵌套逻辑
        public fun onAction(action: NodeAction) {
            when (action) {
                is NodeAction.FieldChanged -> changeField(action)
                is NodeAction.TagAdded ->
                    update { it.copy(tags = (it.tags + action.tag.trim()).distinct()) }
                is NodeAction.TagRemoved ->
                    update { it.copy(tags = it.tags - action.tag) }
                NodeAction.Save -> save()
                NodeAction.ShowVersions -> showVersions()
                NodeAction.HideVersions -> update { it.copy(showVersions = false, diffVersion = null) }
                is NodeAction.ShowDiff ->
                    update { s -> s.copy(diffVersion = s.versions.firstOrNull { v -> v.id == action.versionId }) }
                NodeAction.HideDiff -> update { it.copy(diffVersion = null) }
                is NodeAction.Revert -> revert(action.versionId)
                NodeAction.Confirm -> confirm()
                NodeAction.UndoLastWrite -> undoLastWrite()
                NodeAction.ShowMore -> update { it.copy(showMore = true) }
                NodeAction.HideMore -> update { it.copy(showMore = false) }
                NodeAction.ShowMove -> update { it.copy(showMore = false, moveTarget = it.path) }
                NodeAction.HideMove -> update { it.copy(moveTarget = null) }
                is NodeAction.Move -> move(action.target)
                NodeAction.Delete -> update { it.copy(showMore = false, confirmDelete = true) }
                NodeAction.ConfirmDelete -> delete()
                NodeAction.DismissDelete -> update { it.copy(confirmDelete = false) }
                NodeAction.Retry -> viewModelScope.launch { load() }
            }
        }

        private fun changeField(action: NodeAction.FieldChanged) {
            update {
                when (action.field) {
                    NodeField.TITLE -> it.copy(title = action.value)
                    NodeField.SUMMARY -> it.copy(summary = action.value.take(it.summaryMaxChars))
                    NodeField.CONTENT -> it.copy(content = action.value)
                }
            }
        }

        private suspend fun load() {
            update { it.copy(loading = true, error = null) }
            val node = store.get(path)
            val versions = store.versions(path, limit = VERSIONS_LIMIT)
            update {
                it.copy(
                    loading = false,
                    exists = node != null,
                    title = node?.title.orEmpty(),
                    summary = node?.summary.orEmpty(),
                    content = node?.content.orEmpty(),
                    tags = node?.tags.orEmpty(),
                    links = node?.links?.map { link -> link.value }.orEmpty(),
                    byAgent = node?.source == MemorySource.AGENT,
                    version = node?.version ?: 0,
                    updatedAt = node?.updatedAt ?: 0,
                    versions = versions.map(::toRow),
                )
            }
        }

        private fun showVersions() {
            update { it.copy(showVersions = true) }
            viewModelScope.launch {
                val versions = store.versions(path, limit = VERSIONS_LIMIT).map(::toRow)
                update { s -> s.copy(versions = versions) }
            }
        }

        private fun save() {
            val s = _uiState.value
            update { it.copy(saving = true, error = null) }
            viewModelScope.launch {
                val result =
                    store.write(
                        MemoryWrite(
                            path = path,
                            mode = if (s.exists) WriteMode.UPDATE else WriteMode.CREATE,
                            title = s.title.ifBlank { null },
                            summary = s.summary.ifBlank { null },
                            content = s.content,
                            tags = s.tags,
                            source = MemorySource.USER,
                        ),
                    )
                when (result) {
                    is Outcome.Ok -> load()
                    is Outcome.Err -> update { it.copy(saving = false, error = result.error.message) }
                }
            }
        }

        private fun revert(versionId: Long) {
            viewModelScope.launch {
                val result = store.revert(versionId)
                if (result is Outcome.Err) {
                    update { it.copy(error = result.error.message) }
                }
                load()
            }
        }

        private fun confirm() {
            viewModelScope.launch {
                store.markConfirmed(path)
                load()
            }
        }

        private fun undoLastWrite() {
            val latest = _uiState.value.versions.firstOrNull { it.source == MemorySource.AGENT.name }
            if (latest != null) {
                revert(latest.id)
            }
        }

        private fun delete() {
            viewModelScope.launch {
                val result = store.delete(path, recursive = true, source = MemorySource.USER, runId = null)
                update {
                    it.copy(
                        confirmDelete = false,
                        exists = false,
                        deleted = result is Outcome.Ok,
                        error = (result as? Outcome.Err)?.error?.message,
                    )
                }
            }
        }

        private fun move(target: String) {
            viewModelScope.launch {
                val parsed =
                    when (val outcome = MemoryPath.parse(target)) {
                        is Outcome.Ok -> outcome.value
                        is Outcome.Err -> {
                            update { it.copy(moveTarget = null, error = outcome.error.message) }
                            return@launch
                        }
                    }
                val result = store.move(path, parsed)
                update { it.copy(moveTarget = null) }
                if (result is Outcome.Err) {
                    update { it.copy(error = result.error.message) }
                } else {
                    load()
                }
            }
        }

        private fun update(transform: (NodeUiState) -> NodeUiState) {
            _uiState.value = transform(_uiState.value)
        }

        private fun toRow(version: com.example.autop.memory.MemoryVersion): VersionRow =
            VersionRow(
                id = version.id,
                version = version.version,
                changeType = version.changeType.name,
                source = version.source.name,
                createdAt = version.createdAt,
                contentBefore = version.contentBefore,
                contentAfter = version.contentAfter,
                runId = version.runId?.value,
            )

        private companion object {
            const val VERSIONS_LIMIT: Int = 50
        }
    }
