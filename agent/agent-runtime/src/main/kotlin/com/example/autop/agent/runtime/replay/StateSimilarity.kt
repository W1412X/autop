/**
 * 回放前状态比对（docs/tech/04 §3.12）：节点语义键 Jaccard × keysWeight
 * + 包名/Activity 相同比例 × appWeight（默认 0.85 / 0.15，与 droidrun 相同）。
 */
package com.example.autop.agent.runtime.replay

import com.example.autop.agent.ReplayStateSample

/** 回放前置状态的相似度打分。 */
internal object StateSimilarity {
    /**
     * 综合得分（0..1）：`keysWeight × Jaccard(keys) + appWeight × 包名/Activity 相同比例`。
     * [ReplayStateSample.semanticsVersion] 不一致时 fingerprint/keys 口径不可比（ADR-0007
     * 决策 9）：跳过 keys 只按包名/Activity 打分，后续步骤按定位器重新解析，
     * 键/指纹差异不记 divergence。
     */
    fun score(
        a: ReplayStateSample,
        b: ReplayStateSample,
        keysWeight: Double,
        appWeight: Double,
    ): Double =
        if (a.semanticsVersion != b.semanticsVersion) {
            appScore(a, b)
        } else {
            keysWeight * jaccard(a.keys, b.keys) + appWeight * appScore(a, b)
        }

    /** 节点语义键集合的 Jaccard（两侧都空视为一致）。 */
    fun jaccard(
        a: List<String>,
        b: List<String>,
    ): Double {
        val sa = a.toSet()
        val sb = b.toSet()
        if (sa.isEmpty() && sb.isEmpty()) return 1.0
        val inter = sa.intersect(sb).size
        val union = sa.size + sb.size - inter
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    /** 包名与 Activity 两个字段中相同的比例（null 与 null 视为相同）。 */
    private fun appScore(
        a: ReplayStateSample,
        b: ReplayStateSample,
    ): Double {
        val same = listOf(a.packageName == b.packageName, a.activity == b.activity).count { it }
        return same / 2.0
    }
}
