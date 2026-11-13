/**
 * [BrowseViewModel] 单测（docs/tech/08 §4.4，N184）：树变更信号即时刷新
 * （agent 写入不再需要重新打开页面）、刷新不重置分页进度。
 */
package com.example.autop.feature.memory.browse

import androidx.lifecycle.SavedStateHandle
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.feature.memory.testing.FakeMemoryStore
import com.example.autop.feature.memory.testing.memoryNode
import com.example.autop.memory.MemoryPath
import com.example.autop.memory.MemorySearch
import com.example.autop.memory.SearchHit
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
class BrowseViewModelTest {
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
    fun `tree change refreshes rows without reopening`() =
        runTest(testDispatcher) {
            val store = FakeMemoryStore(nodes = listOf(memoryNode("/a", "旧条目")))
            val viewModel = createViewModel(store)
            assertThat(
                viewModel.uiState.value.rows
                    .map { it.title },
            ).containsExactly("旧条目")

            store.nodes = listOf(memoryNode("/a", "新条目"), memoryNode("/a", "旧条目"))
            store.emitTreeChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            assertThat(
                viewModel.uiState.value.rows
                    .map { it.title },
            ).containsExactly("新条目", "旧条目")
                .inOrder()
        }

    @Test
    fun `refresh keeps already loaded pages`() =
        runTest(testDispatcher) {
            val store = FakeMemoryStore(nodes = (1..35).map { memoryNode("/n$it", "条目 $it") })
            val viewModel = createViewModel(store)
            viewModel.onAction(BrowseAction.LoadMore)
            assertThat(viewModel.uiState.value.rows).hasSize(35)

            store.nodes = listOf(memoryNode("/n0", "最新")) + store.nodes
            store.emitTreeChange()
            advanceTimeBy(DEBOUNCE_WAIT_MS)

            assertThat(viewModel.uiState.value.rows).hasSize(35)
            assertThat(
                viewModel.uiState.value.rows
                    .first()
                    .title,
            ).isEqualTo("最新")
        }

    private fun createViewModel(store: FakeMemoryStore): BrowseViewModel =
        BrowseViewModel(
            store = store,
            search = FakeMemorySearch(),
            configProvider = FakeConfigProvider(),
            savedState = SavedStateHandle(),
        )

    private companion object {
        /** 略大于 REFRESH_DEBOUNCE_MS（300ms），确保去抖后的刷新已执行。 */
        const val DEBOUNCE_WAIT_MS = 500L
    }
}

/** 只用 search 的 [MemorySearch] 假实现。 */
private class FakeMemorySearch : MemorySearch {
    override suspend fun search(
        query: String,
        prefix: MemoryPath?,
        k: Int,
    ): List<SearchHit> = emptyList()

    override suspend fun prefetch(
        query: String,
        k: Int,
        minKeywordScore: Double,
    ): List<SearchHit> = emptyList()
}
