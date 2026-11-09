/**
 * 证据提取器的验收用例（docs/design/flows-contracts-evidence §3.2/§3.3）：
 * 差异集合、持续元素、候选打分（区分度 / 稳定性 / 易变惩罚）与弱证据。
 */
package com.example.autop.flow.core.evidence

import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class EvidenceExtractorTest {
    private val extractor = EvidenceExtractor()

    private fun node(
        key: String,
        text: String? = null,
        act: Boolean = false,
        vid: String? = null,
    ) = ScreenNode(
        tag = "text",
        text = text,
        vid = vid,
        act = if (act) listOf("click") else emptyList(),
        bounds = Rect(0, 0, 10, 10),
        key = key,
    )

    private fun screen(nodes: List<ScreenNode>) = EvidenceScreen(app = "com.demo", page = "首页", nodes = nodes)

    @Test
    fun `appeared element becomes a top candidate and persists`() {
        val pre = screen(listOf(node("k1", "首页")))
        val post = screen(listOf(node("k1", "首页"), node("k2", "已发送", act = true)))
        val later = screen(listOf(node("k1", "首页"), node("k2", "已发送", act = true)))
        val steps =
            listOf(
                EvidenceStep(tool = "tap", pre = pre, post = post),
                EvidenceStep(tool = "wait", pre = post, post = later),
            )

        val step = extractor.extract(listOf(steps)).steps.first()

        assertThat(step.appeared).containsExactly("已发送")
        assertThat(step.persistent).containsExactly("已发送")
        val top = step.candidates.first()
        assertThat(top.label).contains("已发送")
        assertThat(top.score).isAtLeast(2.0)
    }

    @Test
    fun `candidate already true before the action gets the lowest score`() {
        val pre = screen(listOf(node("k1", "首页")))
        val post = screen(listOf(node("k1", "首页"), node("k2", "首页")))
        val steps = listOf(EvidenceStep(tool = "tap", pre = pre, post = post))

        val step = extractor.extract(listOf(steps)).steps.first()

        val home = step.candidates.first { it.label.contains("首页") }
        assertThat(home.score).isLessThan(0.0)
    }

    @Test
    fun `stability across runs rewards consistent candidates`() {
        val run =
            listOf(
                EvidenceStep(
                    tool = "tap",
                    pre = screen(listOf()),
                    post = screen(listOf(node("k2", "已发送", act = true))),
                ),
            )
        val pack = extractor.extract(listOf(run, run))

        val top =
            pack.steps
                .first()
                .candidates
                .first()
        assertThat(top.label).contains("已发送")
        assertThat(top.score).isAtLeast(4.0)
    }

    @Test
    fun `volatile labels and hit data are penalized`() {
        val pre = screen(listOf(node("k1", "首页")))
        val hit =
            buildJsonObject {
                put("hit", buildJsonObject { put("text", "99+") })
            }
        val steps =
            listOf(
                EvidenceStep(
                    tool = "tap",
                    hit = hit,
                    pre = pre,
                    post = screen(listOf(node("k1", "首页"), node("k2", "99+", act = true))),
                ),
            )

        val step = extractor.extract(listOf(steps)).steps.first()

        assertThat(step.hit).isEqualTo("99+")
        val badge = step.candidates.first { it.label.contains("99+") }
        assertThat(badge.score).isLessThan(3.0)
    }

    @Test
    fun `weak screens mark the step and produce no candidates`() {
        val steps =
            listOf(
                EvidenceStep(tool = "tap", pre = EvidenceScreen(weak = true), post = EvidenceScreen(weak = true)),
            )

        val step = extractor.extract(listOf(steps)).steps.first()

        assertThat(step.weak).isTrue()
        assertThat(step.candidates).isEmpty()
    }

    @Test
    fun `app change yields an app candidate`() {
        val steps =
            listOf(
                EvidenceStep(
                    tool = "open_app",
                    pre = EvidenceScreen(app = "com.a", nodes = listOf(node("k1", "首页"))),
                    post = EvidenceScreen(app = "com.b", nodes = listOf(node("k2", "设置"))),
                ),
            )

        val step = extractor.extract(listOf(steps)).steps.first()

        assertThat(step.appChange).isTrue()
        assertThat(step.candidates.any { it.label.contains("com.b") }).isTrue()
    }
}
