/**
 * 条目编辑页 ViewModel（docs/tech/08 §4.4）：编辑保存、版本历史与恢复、
 * agent 写入的确认 / 撤销、删除与移动。新建模式（路由带父目录）下，首次保存时在父目录下创建条目或目录，
 * 之后页面切换为普通编辑。
 */
package com.example.autop.feature.memory.node

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.MemoryConfig
import com.example.autop.feature.memory.MEMORY_NEW_DIR_ARG
import com.example.autop.feature.memory.MEMORY_NEW_PARENT_ARG
import com.example.autop.feature.memory.MEMORY_PATH_ARG
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySource
import com.example.autop.memory.MemoryStore
import com.example.autop.memory.MemoryWrite
import com.example.autop.memory.WriteMode
import com.example.autop.memory.isAutomated
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
        /** 新建模式的父目录；编辑已有条目时为 null。 */
        private val newParent: MemoryPath? = savedState.get<String>(MEMORY_NEW_PARENT_ARG)?.let { parsePath(it) }
        private val newIsDirectory: Boolean = savedState.get<String>(MEMORY_NEW_DIR_ARG).toBoolean()

        /** 当前编辑的条目路径；新建模式下首次保存成功前为 null。 */
        private var nodePath: MemoryPath? =
            if (newParent == null) parsePath(savedState.get<String>(MEMORY_PATH_ARG) ?: "/") else null

        private val _uiState = MutableStateFlow(initialState())
        public val uiState: StateFlow<NodeUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch { load() }
        }

        /** 分派用户动作。 */
        @Suppress("CyclomaticComplexMethod") // 纯动作分派 when，分支数即动作数，无嵌套逻辑
        public fun onAction(action: NodeAction) {
            when (action) {
                is NodeAction.FieldChanged -> changeField(action)
                is NodeAction.KeywordAdded ->
                    update { it.copy(keywords = (it.keywords + action.keyword.trim()).distinct()) }
                is NodeAction.KeywordRemoved ->
                    update { it.copy(keywords = it.keywords - action.keyword) }
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

        private fun initialState(): NodeUiState {
            val parent = newParent
            return NodeUiState(
                loading = parent == null,
                path = (nodePath ?: parent)?.value ?: MemoryPath.ROOT.value,
                isNew = parent != null,
                creatingDirectory = parent != null && newIsDirectory,
                summaryMaxChars = configProvider.current()[MemoryConfig.SUMMARY].maxChars,
            )
        }

        private fun changeField(action: NodeAction.FieldChanged) {
            update {
                when (action.field) {
                    NodeField.TITLE -> it.copy(title = action.value, titleInvalid = false)
                    NodeField.SUMMARY -> it.copy(summary = action.value.take(it.summaryMaxChars))
                    NodeField.CONTENT -> it.copy(content = action.value)
                }
            }
        }

        private suspend fun load() {
            val target = nodePath
            if (target == null) {
                update { it.copy(loading = false) }
                return
            }
            update { it.copy(loading = true, error = null) }
            val node = store.get(target)
            val versions = store.versions(target, limit = VERSIONS_LIMIT)
            update {
                it.copy(
                    loading = false,
                    saving = false,
                    isNew = false,
                    creatingDirectory = false,
                    path = target.value,
                    exists = node != null,
                    title = node?.title.orEmpty(),
                    summary = node?.summary.orEmpty(),
                    content = node?.content.orEmpty(),
                    keywords = node?.keywords.orEmpty(),
                    links = node?.links?.map { link -> link.value }.orEmpty(),
                    byAgent = node?.source?.isAutomated == true,
                    version = node?.version ?: 0,
                    updatedAt = node?.updatedAt ?: 0,
                    versions = versions.map(::toRow),
                )
            }
        }

        private fun showVersions() {
            update { it.copy(showVersions = true) }
            withNode { target ->
                val versions = store.versions(target, limit = VERSIONS_LIMIT).map(::toRow)
                update { s -> s.copy(versions = versions) }
            }
        }

        private fun save() {
            val state = _uiState.value
            val target = nodePath
            if (target != null) {
                writeNode(target, state, creating = !state.exists)
            } else {
                createNode(state)
            }
        }

        /** 新建模式的首次保存：标题即路径段，不允许空白或含 `/`。 */
        private fun createNode(state: NodeUiState) {
            val parent = newParent ?: return
            val name = state.title.trim()
            if (name.isEmpty() || '/' in name) {
                update { it.copy(titleInvalid = true) }
                return
            }
            when (val parsed = MemoryPath.parse("${parent.value}/$name")) {
                is Outcome.Ok -> writeNode(parsed.value, state, creating = true)
                is Outcome.Err -> update { it.copy(error = parsed.error.message) }
            }
        }

        private fun writeNode(
            target: MemoryPath,
            state: NodeUiState,
            creating: Boolean,
        ) {
            update { it.copy(saving = true, error = null) }
            viewModelScope.launch {
                val result =
                    store.write(
                        MemoryWrite(
                            path = target,
                            mode = if (creating) WriteMode.CREATE else WriteMode.UPDATE,
                            title = state.title.trim().ifBlank { null },
                            summary = state.summary.ifBlank { null },
                            content = contentFor(state, creating),
                            keywords = state.keywords,
                            source = MemorySource.USER,
                        ),
                    )
                when (result) {
                    is Outcome.Ok -> {
                        nodePath = target
                        load()
                    }
                    is Outcome.Err -> update { it.copy(saving = false, error = result.error.message) }
                }
            }
        }

        /** 目录＝内容空白：新建条目留空正文时以标题兜底，避免条目被误建成目录。 */
        private fun contentFor(
            state: NodeUiState,
            creating: Boolean,
        ): String =
            when {
                !creating || newParent == null -> state.content
                newIsDirectory -> ""
                else -> state.content.ifBlank { state.title.trim() }
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
            withNode { target ->
                store.markConfirmed(target)
                load()
            }
        }

        private fun undoLastWrite() {
            val latest =
                _uiState.value.versions.firstOrNull {
                    it.source == MemorySource.AGENT.name || it.source == MemorySource.FLOW.name
                }
            if (latest != null) {
                revert(latest.id)
            }
        }

        private fun delete() {
            withNode { target ->
                val result = store.delete(target, recursive = true, source = MemorySource.USER, runId = null)
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
            withNode { from ->
                val parsed =
                    when (val outcome = MemoryPath.parse(target)) {
                        is Outcome.Ok -> outcome.value
                        is Outcome.Err -> {
                            update { it.copy(moveTarget = null, error = outcome.error.message) }
                            return@withNode
                        }
                    }
                val result = store.move(from, parsed)
                update { it.copy(moveTarget = null) }
                if (result is Outcome.Err) {
                    update { it.copy(error = result.error.message) }
                } else {
                    nodePath = parsed
                    load()
                }
            }
        }

        /** 对已存在的条目执行 [block]；新建模式下尚未创建，直接忽略。 */
        private fun withNode(block: suspend (MemoryPath) -> Unit) {
            val target = nodePath ?: return
            viewModelScope.launch { block(target) }
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

            fun parsePath(raw: String): MemoryPath = MemoryPath.parse(raw).getOrNull() ?: MemoryPath.ROOT
        }
    }
