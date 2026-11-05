package com.example.autop.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** RankFusion.rrf 的融合语义（docs/tech/06 §2.6、docs/tech/04 §2.5）。 */
class RankFusionTest {
    @Test
    fun `two channels merge by rrf score`() {
        val fused =
            RankFusion.rrf(
                lists =
                    mapOf(
                        "a" to listOf(Ranked("x", 1), Ranked("y", 2)),
                        "b" to listOf(Ranked("y", 1), Ranked("z", 2)),
                    ),
                weights = mapOf("a" to 1.0, "b" to 1.0),
                k = 60,
            )
        // y 同时命中两个通道（1/62 + 1/61）分数最高，x（1/61）> z（1/62）。
        assertThat(fused.map { it.key }).containsExactly("y", "x", "z").inOrder()
        assertThat(fused.first { it.key == "y" }.channels).containsExactly("a", "b")
        assertThat(fused.first { it.key == "z" }.channels).containsExactly("b")
    }

    @Test
    fun `weights change the order`() {
        val fused =
            RankFusion.rrf(
                lists =
                    mapOf(
                        "a" to listOf(Ranked("x", 1)),
                        "b" to listOf(Ranked("y", 1)),
                    ),
                weights = mapOf("a" to 1.0, "b" to 3.0),
                k = 60,
            )
        assertThat(fused.map { it.key }).containsExactly("y", "x").inOrder()
    }

    @Test
    fun `single channel keeps its order`() {
        val fused =
            RankFusion.rrf(
                lists = mapOf("only" to listOf(Ranked("p", 1), Ranked("q", 2), Ranked("r", 3))),
                weights = emptyMap(),
                k = 60,
            )
        assertThat(fused.map { it.key }).containsExactly("p", "q", "r").inOrder()
        assertThat(fused.map { it.score })
            .containsExactly(1.0 / 61, 1.0 / 62, 1.0 / 63)
            .inOrder()
    }
}
