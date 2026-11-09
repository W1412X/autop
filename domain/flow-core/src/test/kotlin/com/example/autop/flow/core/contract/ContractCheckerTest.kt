/**
 * 契约核验的验收用例（docs/design/flows-contracts-evidence §5）：
 * C1 结果契约动作后成立、C2 动作前不成立、C3 生效前置动作前成立、C4 易变值、C7 缺契约。
 */
package com.example.autop.flow.core.contract

import com.example.autop.device.Rect
import com.example.autop.device.ScreenNode
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.ToolNode
import com.example.autop.flow.core.evidence.EvidenceScreen
import com.example.autop.flow.core.evidence.EvidenceStep
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ContractCheckerTest {
    private val checker = ContractChecker()

    private fun node(
        id: String,
        expect: Expect? = null,
    ) = ToolNode(id = id, tool = "tap", expect = expect)

    private fun screen(text: String): EvidenceScreen =
        EvidenceScreen(
            app = "com.demo",
            nodes = listOf(ScreenNode(tag = "text", text = text, bounds = Rect(0, 0, 10, 10), key = "k1")),
        )

    private fun step(
        pre: String,
        post: String,
    ) = EvidenceStep(tool = "tap", pre = screen(pre), post = screen(post))

    private fun textExpect(value: String) = Expect(kind = ExpectKind.TEXT, text = value)

    private fun flow(vararg nodes: ToolNode) = FlowDefinition(id = "f1", name = "t", steps = nodes.toList())

    @Test
    fun `clean flow with matching evidence has no issues`() {
        val issues =
            checker.check(flow(node("n1", textExpect("已发送"))), listOf(listOf(step("别的", "已发送")))) {
                if (it.id == "n1") 0 else null
            }
        assertThat(issues).isEmpty()
    }

    @Test
    fun `contract unmet after the action is an error`() {
        val issues =
            checker.check(flow(node("n1", textExpect("已发送"))), listOf(listOf(step("别的", "没到")))) {
                if (it.id == "n1") 0 else null
            }
        assertThat(issues.map { it.rule to it.level }).containsExactly("C1" to IssueLevel.ERROR)
    }

    @Test
    fun `contract already true before the action is a warning`() {
        val issues =
            checker.check(flow(node("n1", textExpect("已发送"))), listOf(listOf(step("已发送", "已发送")))) {
                if (it.id == "n1") 0 else null
            }
        assertThat(issues.map { it.rule to it.level }).containsExactly("C2" to IssueLevel.WARNING)
    }

    @Test
    fun `inherited pre unmet before the action is an error`() {
        val steps = listOf(step("别的", "已发送"), step("还是别的", "已发送"))
        val issues =
            checker.check(flow(node("n1", textExpect("已发送")), node("n2", textExpect("已发送"))), listOf(steps)) {
                when (it.id) {
                    "n1" -> 0
                    "n2" -> 1
                    else -> null
                }
            }
        assertThat(issues.any { it.rule == "C3" && it.level == IssueLevel.ERROR }).isTrue()
    }

    @Test
    fun `volatile contract values are a warning`() {
        val step =
            EvidenceStep(
                tool = "tap",
                hit =
                    buildJsonObject {
                        put("hit", buildJsonObject { put("text", "99+") })
                    },
                pre = screen("别的"),
                post = screen("99+"),
            )
        val issues =
            checker.check(flow(node("n1", textExpect("99+"))), listOf(listOf(step))) {
                if (it.id == "n1") 0 else null
            }
        assertThat(issues.any { it.rule == "C4" && it.level == IssueLevel.WARNING }).isTrue()
    }

    @Test
    fun `missing result contract warns and unevidenced nodes are skipped`() {
        val issues =
            checker.check(flow(node("n1"), node("n2", textExpect("已发送"))), listOf(listOf(step("别的", "已发送")))) {
                if (it.id == "n2") 0 else null
            }
        assertThat(issues.map { it.rule to it.nodeId }).containsExactly("C7" to "n1")
    }
}
