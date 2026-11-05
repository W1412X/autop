/**
 * 操作记录页 ViewModel（docs/tech/08 §4.5"操作记录"）：
 * 经 [AuditLogReader.page] 分页读取 `audit_log`（工具调用的策略决策记录）；
 * 持续订阅而非一次性取数（N184）：`AuditDao.page` 是 Room 失效跟踪 Flow，
 * `.first()` 会丢掉重发，运行中产生的审计记录就只能等重进页面才显示。
 */
package com.example.autop.feature.settings.me.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.task.AuditEntry
import com.example.autop.task.AuditLogReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 每页条数。 */
private const val PAGE_SIZE = 50

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest 为实验 API
@HiltViewModel
public class AuditViewModel
    @Inject
    constructor(
        private val auditLog: AuditLogReader,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val entries = MutableStateFlow<List<AuditEntry>>(emptyList())
        private val loading = MutableStateFlow(false)
        private val endReached = MutableStateFlow(false)
        private val pages = MutableStateFlow(1)

        public val uiState: StateFlow<AuditUiState> =
            combine(entries, loading, endReached) { list, busy, end ->
                AuditUiState(entries = list, loading = busy, endReached = end)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = AuditUiState(),
            )

        init {
            viewModelScope.launch(dispatchers.io) {
                pages
                    .flatMapLatest { count -> auditLog.page(offset = 0, limit = PAGE_SIZE * count) }
                    .collect { list ->
                        entries.value = list
                        endReached.value = list.size < PAGE_SIZE * pages.value
                        loading.value = false
                    }
            }
        }

        public fun onAction(action: AuditAction) {
            when (action) {
                AuditAction.LoadMore -> {
                    if (loading.value || endReached.value) return
                    loading.value = true
                    pages.update { it + 1 }
                }
                // 持续订阅下数据本就新鲜；回第一页并重查。
                AuditAction.Refresh -> pages.update { 1 }
            }
        }
    }
