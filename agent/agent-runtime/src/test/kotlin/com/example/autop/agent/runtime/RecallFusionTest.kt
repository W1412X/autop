/**
 * 04 §6 项 15：ThreadRecall 融合——两个通道按 RRF 合并（重叠候选分数相加）；
 * 不可用的通道被跳过；只有一个通道时结果顺序与该通道一致。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.RecallCandidate
import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.agent.runtime.recall.DefaultThreadRecall
import com.example.autop.core.common.ThreadId
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecallFusionTest {
    @Test
    fun `15a 两个通道的结果按 RRF 融合`() =
        runTest {
            val fts =
                FakeRetriever(
                    "recall.fts",
                    listOf(RecallCandidate(10, 1, "甲"), RecallCandidate(20, 2, "乙")),
                )
            val vector =
                FakeRetriever(
                    "recall.vector",
                    listOf(RecallCandidate(20, 1, "乙"), RecallCandidate(30, 2, "丙")),
                )
            val recall = DefaultThreadRecall(setOf(fts, vector), FakeConfigProvider())

            val hits = recall.search(THREAD, "查询", k = 10)

            // 20 双通道命中分数最高；10（rank 1）高于 30（rank 2）
            assertThat(hits.map { it.seq }).containsExactly(20L, 10L, 30L).inOrder()
            assertThat(hits[0].channels).containsExactly("recall.fts", "recall.vector")
            assertThat(hits[1].channels).containsExactly("recall.fts")
            assertThat(hits[2].channels).containsExactly("recall.vector")
        }

    @Test
    fun `15b 不可用的通道被跳过`() =
        runTest {
            val fts = FakeRetriever("recall.fts", listOf(RecallCandidate(10, 1, "甲")))
            val vector = FakeRetriever("recall.vector", listOf(RecallCandidate(99, 1, "乙")), available = false)
            val recall = DefaultThreadRecall(setOf(fts, vector), FakeConfigProvider())

            val hits = recall.search(THREAD, "查询", k = 10)

            assertThat(hits.map { it.seq }).containsExactly(10L)
            assertThat(hits.single().channels).containsExactly("recall.fts")
        }

    @Test
    fun `15c 只有一个通道时顺序与该通道一致`() =
        runTest {
            val fts =
                FakeRetriever(
                    "recall.fts",
                    listOf(RecallCandidate(30, 1, "a"), RecallCandidate(10, 2, "b"), RecallCandidate(20, 3, "c")),
                )
            val recall = DefaultThreadRecall(setOf(fts), FakeConfigProvider())

            val hits = recall.search(THREAD, "查询", k = 10)

            assertThat(hits.map { it.seq }).containsExactly(30L, 10L, 20L).inOrder()
            assertThat(hits.map { it.snippet }).containsExactly("a", "b", "c").inOrder()
        }

    private class FakeRetriever(
        override val id: String,
        private val results: List<RecallCandidate>,
        private val available: Boolean = true,
    ) : ThreadRecallRetriever {
        override suspend fun isAvailable(): Boolean = available

        override suspend fun search(
            threadId: ThreadId,
            query: String,
            limit: Int,
        ): List<RecallCandidate> = results.take(limit)
    }

    private companion object {
        val THREAD: ThreadId = ThreadId("t1")
    }
}
