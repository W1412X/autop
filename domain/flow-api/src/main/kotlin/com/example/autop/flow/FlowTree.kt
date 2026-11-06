package com.example.autop.flow

/** 步骤树遍历工具。 */
public object FlowTree {
    /** 深度优先展开全部节点（含 if/switch/loop 的子步骤与 onFail 兜底步骤）。 */
    public fun allNodes(flow: FlowDefinition): List<Node> =
        flatten(flow.steps) +
            flow.settings.onFail
                ?.steps
                .orEmpty()
                .let { flatten(it) }

    public fun countNodes(flow: FlowDefinition): Int = allNodes(flow).size

    private fun flatten(steps: List<Node>): List<Node> =
        steps.flatMap { node ->
            val children =
                when (node) {
                    is IfNode -> node.thenSteps + node.elseSteps
                    is SwitchNode -> node.cases.flatMap { it.steps } + node.default
                    is LoopNode -> node.steps
                    else -> emptyList()
                }
            listOf(node) + flatten(children + node.onFail?.steps.orEmpty())
        }
}
