/**
 * 历史检索融合（docs/tech/04 §2.5）：按配置启用的通道逐个检索，
 * 用 `RankFusion.rrf` 融合后取 top k。不可用的通道跳过，不报错。
 */
package com.example.autop.agent.runtime.recall

import com.example.autop.agent.RecallCandidate
import com.example.autop.agent.RecallHit
import com.example.autop.agent.ThreadRecall
import com.example.autop.agent.ThreadRecallRetriever
import com.example.autop.core.common.RankFusion
import com.example.autop.core.common.Ranked
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider

/**
 * 融合入口实现。只依赖检索通道与配置（类型字段由 history_recall 工具按事件回查，
 * 见 questions.md），因此不依赖 ThreadStore。
 */
internal class DefaultThreadRecall(
    private val retrievers: Set<ThreadRecallRetriever>,
    private val config: ConfigProvider,
) : ThreadRecall {
    override suspend fun search(
        threadId: ThreadId,
        query: String,
        k: Int,
    ): List<RecallHit> {
        val cfg = config.current()[AgentConfig.RECALL]
        val lists = mutableMapOf<String, List<Ranked<Long>>>()
        val snippets = mutableMapOf<Long, String>()
        for (channelId in cfg.channels) {
            val candidates = candidatesOf(channelId, threadId, query, cfg.perChannelLimit)
            if (candidates.isNotEmpty()) {
                lists[channelId] = candidates.map { Ranked(key = it.seq, rank = it.rank) }
                fillSnippets(candidates, snippets)
            }
        }
        return RankFusion
            .rrf(lists, cfg.weights, cfg.rrfK)
            .take(k)
            .map { fused ->
                RecallHit(
                    seq = fused.key,
                    type = "",
                    snippet = snippets[fused.key].orEmpty(),
                    score = fused.score,
                    channels = fused.channels,
                )
            }
    }

    /** 单通道检索：无对应检索器或不可用时返回空（docs/tech/04 §2.5）。 */
    private suspend fun candidatesOf(
        channelId: String,
        threadId: ThreadId,
        query: String,
        limit: Int,
    ): List<RecallCandidate> {
        val retriever = retrievers.firstOrNull { it.id == channelId }
        return if (retriever != null && retriever.isAvailable()) {
            retriever.search(threadId, query, limit)
        } else {
            emptyList()
        }
    }

    /** 收集 snippet（每个 seq 只保留首见）。 */
    private fun fillSnippets(
        candidates: List<RecallCandidate>,
        snippets: MutableMap<Long, String>,
    ) {
        for (candidate in candidates) {
            candidate.snippet?.let { snippets.putIfAbsent(candidate.seq, it) }
        }
    }
}
