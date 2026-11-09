/**
 * 证据提取器（docs/design/flows-contracts-evidence §3.2/§3.3）：从逐步的动作前后
 * 屏幕计算差异、持续元素与结果契约候选。代码只提候选并打分，最终选择留给创作模型。
 */
package com.example.autop.flow.core.evidence

import com.example.autop.device.NodeDiffSets
import com.example.autop.device.ScreenNode
import com.example.autop.device.nodeDiffSets
import com.example.autop.flow.Condition
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.TextOp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个结果契约候选（含打分，见 §3.3）。 */
@Serializable
public data class ContractCandidate(
    val cond: Condition,
    val score: Double,
    val label: String,
)

/** 单步证据分析结果。 */
@Serializable
public data class StepEvidence(
    val index: Int,
    val tool: String,
    val ok: Boolean,
    val weak: Boolean,
    val appChange: Boolean,
    val appeared: List<String>,
    val disappeared: List<String>,
    val changed: List<String>,
    val persistent: List<String>,
    val hit: String?,
    val candidates: List<ContractCandidate>,
)

/** 一次提取的证据包（逐步）。 */
@Serializable
public data class EvidencePack(
    val steps: List<StepEvidence>,
)

/** 证据提取器。 */
public class EvidenceExtractor(
    private val volatilePatterns: List<Regex> = defaultVolatilePatterns(),
    private val maxCandidates: Int = MAX_CANDIDATES,
) {
    /** [runs] 为多次运行的步骤序列（每条按时间有序）；稳定性按跨运行一致性打分。 */
    public fun extract(runs: List<List<EvidenceStep>>): EvidencePack {
        val primary = runs.firstOrNull().orEmpty()
        return EvidencePack(
            steps =
                primary.mapIndexed { i, step ->
                    val diff = diffOf(step)
                    StepEvidence(
                        index = i,
                        tool = step.tool,
                        ok = step.ok,
                        weak = step.post?.nodes.isNullOrEmpty(),
                        appChange = appChangeOf(step),
                        appeared = summarizeAll(diff.added),
                        disappeared = summarizeAll(diff.removed),
                        changed = summarizeAll(diff.changed),
                        persistent = persistentOf(primary, i, diff),
                        hit = hitSummary(step.hit),
                        candidates = candidatesOf(step, diff, runs, i, laterPostsOf(primary, i)),
                    )
                },
        )
    }

    private fun diffOf(step: EvidenceStep): NodeDiffSets {
        val post = step.post ?: return NodeDiffSets(emptyList(), emptyList(), emptyList())
        return nodeDiffSets(step.pre?.nodes.orEmpty(), post.nodes)
    }

    private fun appChangeOf(step: EvidenceStep): Boolean {
        val pre = step.pre?.app
        val post = step.post?.app
        return pre != null && post != null && pre != post
    }

    /** 后续步骤的动作后屏幕（持续性判定用）。 */
    private fun laterPostsOf(
        steps: List<EvidenceStep>,
        index: Int,
    ): List<EvidenceScreen> = steps.drop(index + 1).mapNotNull { it.post }

    /** 持续元素：本步动作后**出现**且在后续每一步里都还在（§3.2 persistent）。 */
    private fun persistentOf(
        steps: List<EvidenceStep>,
        index: Int,
        diff: NodeDiffSets,
    ): List<String> {
        val later = laterPostsOf(steps, index)
        if (later.isEmpty()) return emptyList()
        return diff.added
            .filter { node -> node.key.isNotEmpty() && later.all { l -> l.nodes.any { it.key == node.key } } }
            .map { summarize(it) }
            .distinct()
            .take(MAX_LIST)
    }

    // ---- 候选与打分（§3.3）------------------------------------------------------------------

    private fun candidatesOf(
        step: EvidenceStep,
        diff: NodeDiffSets,
        runs: List<List<EvidenceStep>>,
        index: Int,
        later: List<EvidenceScreen>,
    ): List<ContractCandidate> {
        val post = step.post ?: return emptyList()
        val app = post.app
        val generated = mutableListOf<ContractCandidate>()
        if (appChangeOf(step) && app != null) {
            generated += candidate(Condition.App(value = app))
        }
        diff.added
            .filter { labelOf(it).isNotEmpty() }
            .take(MAX_APPEARED)
            .forEach { node ->
                val label = labelOf(node)
                if (node.act.isNotEmpty() || !node.vid.isNullOrBlank()) {
                    generated += candidate(Condition.Element(target = Target(by = TargetBy.TEXT, value = label)))
                }
                generated += candidate(Condition.ScreenText(op = TextOp.CONTAINS, value = label))
            }
        if (appChangeOf(step) && app != null && generated.isNotEmpty()) {
            generated += candidate(Condition.All(of = listOf(Condition.App(value = app), generated.first().cond)))
        }
        val hit = hitSummary(step.hit)
        return generated
            .distinctBy { it.label }
            .map { it.copy(score = scoreOf(it, step, runs, index, later, hit)) }
            .sortedByDescending { it.score }
            .take(maxCandidates)
    }

    private fun candidate(cond: Condition): ContractCandidate = ContractCandidate(cond, 0.0, describeCondition(cond))

    /**
     * 打分：区分度（动作后成立、动作前不成立）＋稳定性（多运行一致）＋持续性
     * ＋简洁性 − 易变/数据专属惩罚（§3.3）。动作前就成立的候选直接降为最低分。
     */
    private fun scoreOf(
        candidate: ContractCandidate,
        step: EvidenceStep,
        runs: List<List<EvidenceStep>>,
        index: Int,
        later: List<EvidenceScreen>,
        hit: String?,
    ): Double {
        val pre = step.pre
        val post = step.post
        val discrimination =
            when {
                pre == null || post == null -> 0.0
                conditionHolds(candidate.cond, post) && !conditionHolds(candidate.cond, pre) -> DISCRIMINATION_BEST
                else -> DISCRIMINATION_WORST
            }
        val checked = runs.mapNotNull { it.getOrNull(index)?.post }
        val stability =
            if (runs.size <= 1) {
                0.0
            } else {
                STABILITY_WEIGHT *
                    checked.count { conditionHolds(candidate.cond, it) }.toDouble() /
                    maxOf(1, checked.size)
            }
        val persistence = if (later.any { conditionHolds(candidate.cond, it) }) PERSIST_WEIGHT else 0.0
        val simplicity = if (candidate.cond is Condition.All) SIMPLICITY_PENALTY else 0.0
        val volatility = if (isVolatile(candidate.label, hit)) VOLATILE_PENALTY else 0.0
        return discrimination + stability + persistence + simplicity + volatility
    }

    /** 易变文本或本次运行特有的命中文本（§3.3 独立于数据）。 */
    private fun isVolatile(
        label: String,
        hit: String?,
    ): Boolean = volatilePatterns.any { it.containsMatchIn(label) } || (hit != null && label == hit)

    private fun labelOf(node: ScreenNode): String =
        listOfNotNull(node.text, node.desc, node.hint, node.icon).firstOrNull().orEmpty()

    private fun summarize(node: ScreenNode): String {
        val label = labelOf(node)
        return if (label.isEmpty()) node.tag else label
    }

    private fun summarizeAll(nodes: List<ScreenNode>): List<String> =
        nodes.map { summarize(it) }.distinct().take(MAX_LIST)

    private fun hitSummary(hit: JsonObject?): String? {
        val inner = hit?.get("hit") as? JsonObject ?: hit
        return inner?.let { obj ->
            listOf("text", "desc", "hint", "icon")
                .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.content }
        }
    }

    private companion object {
        const val MAX_CANDIDATES: Int = 8
        const val MAX_APPEARED: Int = 5
        const val MAX_LIST: Int = 8
        const val DISCRIMINATION_BEST: Double = 2.0
        const val DISCRIMINATION_WORST: Double = -2.0
        const val STABILITY_WEIGHT: Double = 2.0
        const val PERSIST_WEIGHT: Double = 1.0
        const val SIMPLICITY_PENALTY: Double = -0.5
        const val VOLATILE_PENALTY: Double = -1.5
    }
}
