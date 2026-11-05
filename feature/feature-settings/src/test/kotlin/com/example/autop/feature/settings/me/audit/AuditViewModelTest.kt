/**
 * [AuditViewModel] 单测（docs/tech/08 §4.5"操作记录"）：分页加载、
 * 末页后不再加载、Refresh 从头重来、新记录无需重进页面即显示（N184）。
 */
package com.example.autop.feature.settings.me.audit

import com.example.autop.core.testing.TestDispatcherProvider
import com.example.autop.task.AuditEntry
import com.example.autop.task.AuditLogReader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuditViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads first page and appends more until end`() =
        runTest(testDispatcher) {
            val reader = FakeAuditLogReader(entries(count = 60))
            val viewModel = createViewModel(reader)
            assertThat(viewModel.uiState.value.entries).hasSize(50)
            assertThat(viewModel.uiState.value.endReached).isFalse()

            viewModel.onAction(AuditAction.LoadMore)
            assertThat(viewModel.uiState.value.entries).hasSize(60)
            assertThat(viewModel.uiState.value.endReached).isTrue()
        }

    @Test
    fun `short first page reaches end immediately`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(FakeAuditLogReader(entries(count = 3)))
            assertThat(viewModel.uiState.value.entries).hasSize(3)
            assertThat(viewModel.uiState.value.endReached).isTrue()
        }

    @Test
    fun `refresh reloads from the start`() =
        runTest(testDispatcher) {
            val reader = FakeAuditLogReader(entries(count = 60))
            val viewModel = createViewModel(reader)
            viewModel.onAction(AuditAction.LoadMore)
            assertThat(viewModel.uiState.value.entries).hasSize(60)

            viewModel.onAction(AuditAction.Refresh)
            assertThat(viewModel.uiState.value.entries).hasSize(50)
        }

    @Test
    fun `new rows appear without reopening the page`() =
        runTest(testDispatcher) {
            val reader = FakeAuditLogReader(entries(count = 3))
            val viewModel = createViewModel(reader)
            assertThat(viewModel.uiState.value.entries).hasSize(3)

            reader.update(entries(count = 5))
            testScheduler.runCurrent()

            assertThat(viewModel.uiState.value.entries).hasSize(5)
        }

    private fun createViewModel(reader: AuditLogReader) = AuditViewModel(reader, TestDispatcherProvider(testDispatcher))
}

private fun entries(count: Int): List<AuditEntry> =
    (1..count).map { index ->
        AuditEntry(
            id = index.toLong(),
            runId = "run-1",
            toolId = "tool.$index",
            targetSummary = "目标 $index",
            decision = if (index % 2 == 0) "blocked" else "allowed",
            ruleId = null,
            ok = true,
            createdAt = index.toLong(),
        )
    }

/** 响应式切页的 [AuditLogReader] 假实现（行变化即重发，模拟 Room 失效跟踪）。 */
private class FakeAuditLogReader(
    rows: List<AuditEntry>,
) : AuditLogReader {
    private val rows = MutableStateFlow(rows)

    override fun page(
        offset: Int,
        limit: Int,
    ): Flow<List<AuditEntry>> = rows.map { it.drop(offset).take(limit) }

    /** 模拟新审计记录落库。 */
    fun update(newRows: List<AuditEntry>) {
        rows.value = newRows
    }
}
