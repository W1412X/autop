/**
 * 状态相似度（docs/tech/04 §2.7，04 §6 第 19 项）：
 * `score = keysWeight × Jaccard(keys) + appWeight × 包名/Activity 相同比例`。
 */
package com.example.autop.agent.runtime.replay

import com.example.autop.agent.ReplayStateSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StateSimilarityTest {
    @Test
    fun `相同样本相似度为 1`() {
        val s = sample(keys = listOf("a", "b"))

        val score = StateSimilarity.score(s, s, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `键集三分之一重叠且包名活动全同时按权重合成`() {
        val a = sample(keys = listOf("a", "b"))
        val b = sample(keys = listOf("a", "c"))

        val score = StateSimilarity.score(a, b, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(0.85 * (1.0 / 3.0) + 0.15 * 1.0)
    }

    @Test
    fun `两侧键集都为空视为全同`() {
        val a = sample(keys = emptyList())
        val b = sample(keys = emptyList())

        val score = StateSimilarity.score(a, b, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `包名不同活动相同算一半`() {
        val a = sample(pkg = "com.a", keys = listOf("a"))
        val b = sample(pkg = "com.b", keys = listOf("a"))

        val score = StateSimilarity.score(a, b, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(0.85 * 1.0 + 0.15 * 0.5)
    }

    @Test
    fun `空字段互等视为相同`() {
        val a = sample(pkg = null, activity = null, keys = listOf("a"))
        val b = sample(pkg = null, activity = null, keys = listOf("a"))

        val score = StateSimilarity.score(a, b, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `语义版本不一致跳过键比对只按包名活动打分`() {
        // ADR-0007 决策 9：新旧口径的 fingerprint/keys 不可比，只比 package/activity。
        val a = sample(keys = listOf("old-key"), semanticsVersion = 1)
        val b = sample(keys = listOf("new-key"), semanticsVersion = 2)

        val score = StateSimilarity.score(a, b, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `语义版本不一致时包名活动差异仍计分`() {
        val a = sample(pkg = "com.a", keys = listOf("k"), semanticsVersion = 1)
        val b = sample(pkg = "com.b", keys = listOf("k"), semanticsVersion = 2)

        val score = StateSimilarity.score(a, b, keysWeight = 0.85, appWeight = 0.15)

        assertThat(score).isWithin(1e-9).of(0.5)
    }

    private fun sample(
        pkg: String? = "com.example",
        activity: String? = "Main",
        keys: List<String>,
        semanticsVersion: Int = 1,
    ): ReplayStateSample =
        ReplayStateSample(
            packageName = pkg,
            activity = activity,
            fingerprint = "fp",
            keys = keys,
            semanticsVersion = semanticsVersion,
            observeMode = "a11y",
        )
}
