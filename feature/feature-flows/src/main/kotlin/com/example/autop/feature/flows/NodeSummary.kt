/**
 * 节点的类型名与一行摘要（编辑器、AI 草稿预览共用）。
 */
@file:Suppress("CyclomaticComplexMethod") // 节点类型分发表

package com.example.autop.feature.flows

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.autop.flow.AgentNode
import com.example.autop.flow.ApiNode
import com.example.autop.flow.BreakNode
import com.example.autop.flow.CallNode
import com.example.autop.flow.ContinueNode
import com.example.autop.flow.EndNode
import com.example.autop.flow.IfNode
import com.example.autop.flow.LlmNode
import com.example.autop.flow.LogNode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.ObserveNode
import com.example.autop.flow.ReadNode
import com.example.autop.flow.SetNode
import com.example.autop.flow.StateNode
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.Target
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitNode

/** 节点类型的稳定 id（与 JSON 的 `type` 一致）。 */
internal fun nodeType(node: Node): String =
    when (node) {
        is ToolNode -> "tool"
        is ObserveNode -> "observe"
        is ReadNode -> "read"
        is WaitNode -> "wait"
        is LlmNode -> "llm"
        is IfNode -> "if"
        is SwitchNode -> "switch"
        is LoopNode -> "loop"
        is BreakNode -> "break"
        is ContinueNode -> "continue"
        is CallNode -> "call"
        is AgentNode -> "agent"
        is ApiNode -> "api"
        is SetNode -> "set"
        is StateNode -> "state"
        is LogNode -> "log"
        is EndNode -> "end"
    }

/** 全部可新增的节点类型（编辑器的"添加节点"菜单顺序）。 */
internal val ADDABLE_TYPES: List<String> =
    listOf(
        "tool",
        "wait",
        "if",
        "loop",
        "llm",
        "read",
        "observe",
        "set",
        "state",
        "call",
        "agent",
        "api",
        "switch",
        "log",
        "end",
        "break",
        "continue",
    )

/** 按资源名取字符串（节点类型、字段名等表驱动文案）；找不到时返回 [fallback]。 */
@Composable
internal fun stringByName(
    name: String,
    fallback: String,
): String {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return if (id != 0) stringResource(id) else fallback
}

/** 节点类型的中文名。 */
@Composable
internal fun nodeTypeLabel(type: String): String = stringByName("flow_node_type_$type", type)

/** 目标描述的简短文字。 */
internal fun targetText(t: Target?): String =
    t?.let { it.value ?: it.tag ?: it.fallback?.at?.joinToString(",") }.orEmpty()

/** 一行摘要（数据本身，不翻译）。 */
internal fun nodeDetail(node: Node): String =
    when (node) {
        is ToolNode -> listOf(node.tool, targetText(node.target)).filter { it.isNotBlank() }.joinToString(" → ")
        is ObserveNode -> node.mode.orEmpty()
        is ReadNode ->
            listOf(node.from.name.lowercase(), node.attr.name.lowercase(), targetText(node.target))
                .filter {
                    it.isNotBlank()
                }.joinToString(" ")
        is WaitNode ->
            listOf(node.waitFor.name.lowercase(), node.value.orEmpty(), targetText(node.target))
                .filter {
                    it.isNotBlank()
                }.joinToString(" ")
        is LlmNode ->
            node.prompt
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .take(DETAIL_CHARS)
        is IfNode -> "${node.thenSteps.size} / ${node.elseSteps.size}"
        is SwitchNode -> node.on
        is LoopNode -> "${node.mode.name.lowercase()} · ${node.steps.size}"
        is BreakNode, is ContinueNode -> ""
        is CallNode -> node.flow
        is AgentNode -> node.goal.take(DETAIL_CHARS)
        is ApiNode -> "${node.method.name} ${node.endpoint}${node.path}"
        is SetNode -> node.vars.keys.joinToString(", ")
        is StateNode -> "${node.op.name.lowercase()} ${node.key}"
        is LogNode -> node.message.take(DETAIL_CHARS)
        is EndNode -> node.status.name.lowercase()
    }

private const val DETAIL_CHARS = 40

/** 直接子节点（用于草稿预览与编辑器的缩进展示）。 */
internal fun childSteps(node: Node): List<Pair<String, List<Node>>> =
    when (node) {
        is IfNode -> listOf("then" to node.thenSteps, "else" to node.elseSteps)
        is SwitchNode -> node.cases.mapIndexed { i, c -> "case${i + 1}" to c.steps } + listOf("default" to node.default)
        is LoopNode -> listOf("body" to node.steps)
        else -> emptyList()
    }
