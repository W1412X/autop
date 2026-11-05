/**
 * 排名融合（docs/tech/06 §2.6、docs/tech/04 §2.5 共用）：
 * `score(item) = Σ channel weight[channel] / (k + rank)`（RRF，rank 从 1 开始）。
 */
package com.example.autop.core.common

/** 某个检索通道给出的一个候选。 */
public data class Ranked<T : Any>(
    val key: T,
    /** 通道内排名，从 1 开始。 */
    val rank: Int,
)

/** 融合结果。 */
public data class FusedRank<T : Any>(
    val key: T,
    val score: Double,
    /** 命中过该候选的通道 id。 */
    val channels: Set<String>,
)

/** RRF 融合入口。 */
public object RankFusion {
    /**
     * 把多个通道的排名列表融合为一个按分数降序的列表（稳定排序，同分保持输入顺序）。
     *
     * @param lists 各通道的候选列表（key 为通道 id）。
     * @param weights 各通道权重；缺省按 1.0。
     * @param k 平滑常数（配置 `*.rrfK`）。
     */
    public fun <T : Any> rrf(
        lists: Map<String, List<Ranked<T>>>,
        weights: Map<String, Double>,
        k: Int,
    ): List<FusedRank<T>> {
        val scores = LinkedHashMap<T, Double>()
        val channels = LinkedHashMap<T, MutableSet<String>>()
        for ((channelId, ranked) in lists) {
            val weight = weights[channelId] ?: DEFAULT_WEIGHT
            for (item in ranked) {
                scores[item.key] = (scores[item.key] ?: 0.0) + weight / (k + item.rank)
                channels.getOrPut(item.key) { mutableSetOf() }.add(channelId)
            }
        }
        return scores.entries
            .map { (key, score) -> FusedRank(key, score, channels.getValue(key).toSet()) }
            .sortedByDescending { it.score }
    }

    private const val DEFAULT_WEIGHT: Double = 1.0
}
