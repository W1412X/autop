/**
 * 契约核验（docs/design/flows-contracts-evidence §5）：把流程草稿放到全部证据上
 * 逐节点判定，不执行任何动作。错误阻止保存，警告不阻止。
 */
package com.example.autop.flow.core.contract

import com.example.autop.flow.Condition
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.IfNode
import com.example.autop.flow.LogNode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.SetNode
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitNode
import com.example.autop.flow.core.evidence.EvidenceStep
import com.example.autop.flow.core.evidence.conditionHolds
import com.example.autop.flow.core.evidence.defaultVolatilePatterns
import com.example.autop.flow.core.evidence.describeCondition
import com.example.autop.flow.core.toCondition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 核验问题级别：错误阻止保存，警告不阻止（§5）。 */
public enum class IssueLevel {
    ERROR,
    WARNING,
}

/** 一条核验问题。 */
public data class ContractIssue(
    val rule: String,
    val level: IssueLevel,
    val nodeId: String?,
    val message: String,
)

/** 契约核验器（规则 C1–C4、C7；C5/C6 属重复片段与参数核验）。 */
public class ContractChecker(
    private val volatilePatterns: List<Regex> = defaultVolatilePatterns(),
) {
    /**
     * 校验 [flow] 在证据上的契约。[stepOf] 给出节点 → 证据步序号（创作元数据 authorTrace）；
     * 无对应证据的节点跳过证据类规则（C1–C4）。
     */
    public fun check(
        flow: FlowDefinition,
        runs: List<List<EvidenceStep>>,
        stepOf: (Node) -> Int?,
    ): List<ContractIssue> {
        val inherited = inheritedMap(flow.steps, mutableMapOf())
        return allNodes(flow.steps).flatMap { checkNode(it, inherited, runs, stepOf) }
    }

    private fun checkNode(
        node: Node,
        inherited: Map<String, Condition?>,
        runs: List<List<EvidenceStep>>,
        stepOf: (Node) -> Int?,
    ): List<ContractIssue> {
        val tool = node as? ToolNode ?: return emptyList()
        val cond = tool.expect?.toCondition()
        if (cond == null) {
            // C7：写屏幕节点缺少结果契约，运行时按弱校验补并标记"未验证"。
            return listOf(ContractIssue("C7", IssueLevel.WARNING, tool.id, "缺少结果契约：${tool.tool}"))
        }
        val index = stepOf(tool) ?: return emptyList()
        val posts = runs.mapNotNull { it.getOrNull(index)?.post }
        val pres = runs.mapNotNull { it.getOrNull(index)?.pre }
        val issues = mutableListOf<ContractIssue>()
        if (posts.any { !conditionHolds(cond, it) }) {
            issues += ContractIssue("C1", IssueLevel.ERROR, tool.id, "结果契约在动作后不成立：${describeCondition(cond)}")
        }
        if (pres.any { conditionHolds(cond, it) }) {
            issues +=
                ContractIssue(
                    "C2",
                    IssueLevel.WARNING,
                    tool.id,
                    "结果契约动作前已成立，证明不了效果：${describeCondition(cond)}",
                )
        }
        val pre = inherited[tool.id]
        if (pre != null && pres.any { !conditionHolds(pre, it) }) {
            issues += ContractIssue("C3", IssueLevel.ERROR, tool.id, "生效前置契约动作前不成立：${describeCondition(pre)}")
        }
        val hit = runs.firstNotNullOfOrNull { it.getOrNull(index)?.hit }
        if (isVolatile(describeCondition(cond), hit)) {
            issues += ContractIssue("C4", IssueLevel.WARNING, tool.id, "契约含易变或本次特有的值")
        }
        return issues
    }

    /** 易变文本或命中的具体数据项（§3.3 独立于数据）。 */
    private fun isVolatile(
        label: String,
        hit: JsonObject?,
    ): Boolean {
        val hitText =
            hit?.let { obj ->
                listOf("text", "desc", "hint", "icon")
                    .firstNotNullOfOrNull { key ->
                        (obj[key] as? JsonPrimitive)?.content
                    }
            }
        return volatilePatterns.any { it.containsMatchIn(label) } || (hitText != null && label.contains(hitText))
    }

    /** 节点 id → 生效前置契约（显式 pre，或继承自上一个工具节点的结果契约，§7.2）。 */
    private fun inheritedMap(
        steps: List<Node>,
        out: MutableMap<String, Condition?>,
    ): Map<String, Condition?> {
        for (i in steps.indices) {
            val node = steps[i]
            out[node.id] = node.pre?.cond ?: inheritedOf(steps, i)
            childLists(node).forEach { inheritedMap(it, out) }
        }
        return out
    }

    private fun inheritedOf(
        steps: List<Node>,
        index: Int,
    ): Condition? {
        for (j in index - 1 downTo 0) {
            when (val prev = steps[j]) {
                is WaitNode, is LogNode, is SetNode -> continue
                is ToolNode -> return prev.expect?.toCondition()
                else -> return null
            }
        }
        return null
    }

    private fun childLists(node: Node): List<List<Node>> =
        when (node) {
            is IfNode -> listOf(node.thenSteps, node.elseSteps)
            is SwitchNode -> node.cases.map { it.steps } + listOf(node.default)
            is LoopNode -> listOf(node.steps)
            else -> emptyList()
        }

    private fun allNodes(steps: List<Node>): List<Node> =
        steps.flatMap { listOf(it) + allNodes(childLists(it).flatten()) }
}
