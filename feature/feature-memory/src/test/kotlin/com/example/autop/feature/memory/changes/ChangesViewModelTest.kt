/**
 * [ChangesViewModel] 单测（docs/tech/08 §4.4，N184）：树变更信号即时刷新
 * （不再需要重新打开页面）、刷新不重置分页进度。
 */
package com.example.autop.feature.memory.changes

import com.example.autop.feature.memory.testing.FakeMemoryStore
import com.example.autop.feature.memory.testing.memoryVersion
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesViewModelTest {
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
    fun `tree change refreshes the list without reopening`() =
        runTest(testDispatcher) {
            val store = FakeMemoryStore(recent = listOf(memoryVersion(1, "旧条目")))
            val viewModel = ChangesViewModel(store)
            assertThat(
                viewModel.uiState.value.groups
                    .flatMap { it.rows }
                    .map { it.title },
            ).containsExactly("旧条目")

            store.recent = listOf(memoryVersion(2, "新条目"), memoryVersion(1, "旧条目"))
            store.emitTreeChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            assertThat(
                viewModel.uiState.value.groups
                    .flatMap { it.rows }
                    .map { it.title },
            ).containsExactly("新条目", "旧条目")
                .inOrder()
        }

    @Test
    fun `refresh keeps already loaded pages`() =
        runTest(testDispatcher) {
            val store = FakeMemoryStore(recent = (1..35).map { memoryVersion(it.toLong(), "条目 $it") })
            val viewModel = ChangesViewModel(store)
            viewModel.onAction(ChangesAction.LoadMore)
            assertThat(
                viewModel.uiState.value.groups
                    .flatMap { it.rows },
            ).hasSize(35)

            store.recent = listOf(memoryVersion(0, "最新")) + store.recent
            store.emitTreeChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            val rows =
                viewModel.uiState.value.groups
                    .flatMap { it.rows }
            assertThat(rows).hasSize(35)
            assertThat(rows.first().title).isEqualTo("最新")
        }

    private companion object {
        /** 略大于 REFRESH_DEBOUNCE_MS（300ms），确保去抖后的刷新已执行。 */
        const val DEBOUNCE_WAIT_MS = 500L
    }
}
