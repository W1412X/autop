/**
 * Thread 事件 → 证据步骤序列（docs/design/flows-contracts-evidence §3.1）：
 * 每个工具结果为一步；动作前屏幕取同一 Thread 上一条带观测的结果，命中元素取 `_hit`。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.RunId
import com.example.autop.flow.core.evidence.EvidencePack
import com.example.autop.flow.core.evidence.EvidenceScreen
import com.example.autop.flow.core.evidence.EvidenceStep
import com.example.autop.flow.core.evidence.EvidenceWireParser
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject

private const val TOOL_PREFIX = "tool."
private const val HIT_KEY = "_hit"

/** 运行事件 → 证据步骤（[runId] 之外的事件跳过；无观测的步骤 post 为空）。 */
internal fun evidenceStepsOf(
    events: List<ThreadEvent>,
    runId: RunId,
): List<EvidenceStep> {
    var last: EvidenceScreen? = null
    return events
        .filter { it.runId == runId }
        .mapNotNull { it.payload as? ToolResultEvent }
        .map { result ->
            val post = screenOf(result)
            val step =
                EvidenceStep(
                    tool = result.toolId.removePrefix(TOOL_PREFIX),
                    args = result.replay?.replayArgs ?: JsonObject(emptyMap()),
                    hit = (result.replay?.replayArgs?.get(HIT_KEY)) as? JsonObject,
                    pre = last,
                    post = post,
                    ok = result.ok,
                )
            if (post != null) last = post
            step
        }
}

/** 证据包附到工具结果 data 上（供创作层与核验使用）。 */
internal fun withEvidence(
    data: JsonObject,
    pack: EvidencePack,
): JsonObject =
    JsonObject(data + ("evidence" to AutopJson.default.encodeToJsonElement(EvidencePack.serializer(), pack)))

/** 结果观测 → 证据屏幕；无结构化 JSON（llm 模式等）时为弱证据。 */
private fun screenOf(result: ToolResultEvent): EvidenceScreen? {
    val ref = result.observation ?: return null
    val text =
        (result.fullBlocks ?: result.blocks)
            .filterIsInstance<Block.Text>()
            .firstOrNull()
            ?.text
            .orEmpty()
    val rows = EvidenceWireParser.parseRows(text)
    return EvidenceScreen(
        app = ref.packageName,
        fingerprint = ref.fingerprint,
        weak = rows.isEmpty(),
        nodes = rows,
    )
}
