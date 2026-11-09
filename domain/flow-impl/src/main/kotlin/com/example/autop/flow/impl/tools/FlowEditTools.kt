/**
 * `tool.create_flow` / `tool.edit_flow`：按参数直接创建与修改流程，不依赖本次运行的
 * 操作轨迹（轨迹式入口是 `save_as_flow` / `update_flow` / `patch_flow_node`）。
 *
 * 步骤只表达线性工具序列（`{tool, args, target}`）；条件分支、循环等复杂结构请经
 * 结果里的快捷入口到流程编辑页修改。创建直接进流程库；修改走版本化修订，旧定义可回滚。
 */
package com.example.autop.flow.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.IfNode
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.Target
import com.example.autop.flow.ToolNode
import com.example.autop.flow.impl.service.FlowRevisionService
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** 按步骤定义创建流程。 */
@Singleton
public class CreateFlowTool
    @Inject
    internal constructor(
        private val store: FlowStore,
        private val validation: FlowValidationService,
        private val ids: IdGenerator,
    ) : Tool {
        override val id: String = "tool.create_flow"
        override val spec: ToolSpec = FlowToolSpecs.createFlow
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val name = args.text("name")?.trim().orEmpty()
            if (name.isEmpty()) return failure(FlowErrorCodes.ARGS_INVALID, "需要 name 参数", "给流程起一个简短的名称")
            val steps =
                when (val parsed = stepNodes(args["steps"])) {
                    is Outcome.Ok -> parsed.value
                    is Outcome.Err -> return failure(parsed.error.code, parsed.error.message, parsed.error.hint)
                }
            if (steps.isEmpty()) return failure(FlowErrorCodes.ARGS_INVALID, "至少要有一个步骤", "steps 里给出工具调用序列")
            val definition =
                FlowDefinition(
                    id = ids.newId(),
                    name = name,
                    description = args.text("description").orEmpty(),
                    steps = steps,
                )
            return persist(definition)
        }

        private suspend fun persist(definition: FlowDefinition): ToolOutput {
            problemsOf(validation, definition)?.let { return failure(FlowErrorCodes.INVALID, it, "修正步骤后重试") }
            return when (val saved = store.save(definition)) {
                is Outcome.Err -> failure(saved.error.code, saved.error.message, saved.error.hint)
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text("已创建流程「${definition.name}」(flowId=${saved.value.id.value})。")),
                        data = flowData(saved.value.id.value),
                    )
            }
        }
    }

/** 修改既有流程的名称、描述或步骤（不提供删除）。 */
@Singleton
public class EditFlowTool
    @Inject
    internal constructor(
        private val store: FlowStore,
        private val validation: FlowValidationService,
        private val revisions: FlowRevisionService,
    ) : Tool {
        override val id: String = "tool.edit_flow"
        override val spec: ToolSpec = FlowToolSpecs.editFlow
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        @Suppress("ReturnCount") // 逐条早退：缺参 / 找不到 / 步骤改法不合法 / 无变更
        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val reference = args.text("flow")?.trim().orEmpty()
            if (reference.isEmpty()) return failure(FlowErrorCodes.ARGS_INVALID, "需要 flow 参数", "传流程 id 或名称")
            val stored =
                store.resolve(reference)
                    ?: return failure(FlowErrorCodes.NOT_FOUND, "找不到流程：$reference", "先调用 list_flows 查看可用流程")
            val steps =
                when (val edited = editedSteps(args, stored.definition.steps)) {
                    is Outcome.Ok -> edited.value
                    is Outcome.Err -> return failure(edited.error.code, edited.error.message, edited.error.hint)
                }
            val updated =
                stored.definition.copy(
                    name = args.text("name")?.trim() ?: stored.definition.name,
                    description = args.text("description") ?: stored.definition.description,
                    steps = steps,
                )
            if (updated == stored.definition) {
                return failure(
                    FlowErrorCodes.ARGS_INVALID,
                    "没有要修改的字段",
                    "给出 name / description / set_step / add_step / remove_step",
                )
            }
            problemsOf(validation, updated)?.let { return failure(FlowErrorCodes.INVALID, it, "修正后重试") }
            return when (val applied = revisions.apply(stored.id.value, updated, reason = null)) {
                is Outcome.Err -> failure(applied.error.code, applied.error.message, applied.error.hint)
                is Outcome.Ok ->
                    ToolOutput(
                        ok = true,
                        content = listOf(Block.Text("已更新流程「${updated.name}」。")),
                        data = flowData(stored.id.value),
                    )
            }
        }
    }

/** `steps` 参数 → 节点列表：每步 `{tool, args, target}`，节点 id 自动编号 `n1…`。 */
@Suppress("ReturnCount") // 逐条早退：元素/工具/目标不合法
private fun stepNodes(steps: JsonElement?): Outcome<List<Node>> {
    val array = steps as? JsonArray ?: return invalid("steps 需要是步骤数组")
    val nodes = mutableListOf<Node>()
    for ((index, element) in array.withIndex()) {
        val step = element as? JsonObject ?: return invalid("第 ${index + 1} 步不是对象")
        val tool = step.text("tool")?.trim().orEmpty()
        if (tool.isEmpty()) return invalid("第 ${index + 1} 步缺少 tool")
        val target =
            when (val raw = step["target"]?.takeIf { it !is JsonNull }) {
                null -> null
                else ->
                    runCatching { AutopJson.default.decodeFromJsonElement(Target.serializer(), raw) }.getOrNull()
                        ?: return invalid("第 ${index + 1} 步的 target 无法解析")
            }
        nodes +=
            ToolNode(
                id = "n${index + 1}",
                tool = tool,
                args = step.obj("args") ?: JsonObject(emptyMap()),
                target = target,
            )
    }
    return Outcome.Ok(nodes)
}

/** `set_step` / `add_step` / `remove_step` → 修订后的顶层步骤列表（只传要改的）。 */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // 三种改法逐条早退
private fun editedSteps(
    args: JsonObject,
    base: List<Node>,
): Outcome<List<Node>> {
    val setStep = args["set_step"] as? JsonObject
    val addStep = args["add_step"] as? JsonObject
    val removeId = args.text("remove_step")
    var steps = base
    if (setStep != null) {
        when (val patched = patchStep(steps, setStep)) {
            is Outcome.Ok -> steps = patched.value
            is Outcome.Err -> return patched
        }
    }
    if (addStep != null) {
        when (val appended = appendStep(steps, addStep)) {
            is Outcome.Ok -> steps = appended.value
            is Outcome.Err -> return appended
        }
    }
    if (removeId != null) {
        if (findNode(steps, removeId) == null) return invalid("找不到节点：$removeId")
        steps = removeNode(steps, removeId)
    }
    return Outcome.Ok(steps)
}

/** `set_step`：找到 [patch] 里的 node_id（含嵌套），覆盖给出的 tool / args / target。 */
private fun patchStep(
    steps: List<Node>,
    patch: JsonObject,
): Outcome<List<Node>> {
    val nodeId = patch.text("node_id").orEmpty()
    val current =
        findNode(steps, nodeId) as? ToolNode
            ?: return invalid("找不到可修改的 tool 节点：$nodeId（先用 list_flows 查看节点 id）")
    val replacement =
        current.copy(
            tool = patch.text("tool") ?: current.tool,
            args = patch.obj("args") ?: current.args,
            target = patch["target"]?.let { targetOf(it) } ?: current.target,
        )
    return Outcome.Ok(replaceNode(steps, nodeId, replacement))
}

/** `add_step`：插到顶层 [JsonObject.text] `after` 节点之后（缺省末尾）。 */
private fun appendStep(
    steps: List<Node>,
    patch: JsonObject,
): Outcome<List<Node>> {
    val tool = patch.text("tool")?.trim().orEmpty()
    if (tool.isEmpty()) return invalid("add_step 需要 tool")
    val node =
        ToolNode(
            id = nextId(steps),
            tool = tool,
            args = patch.obj("args") ?: JsonObject(emptyMap()),
            target = patch["target"]?.let { targetOf(it) },
        )
    val after = patch.text("after") ?: return Outcome.Ok(steps + node)
    val index = steps.indexOfFirst { it.id == after }
    if (index < 0) return invalid("after 指定的节点不在顶层：$after")
    return Outcome.Ok(steps.toMutableList().apply { add(index + 1, node) })
}

/** 目标对象解析；显式 null 视为无目标。 */
private fun targetOf(element: JsonElement): Target? =
    runCatching { AutopJson.default.decodeFromJsonElement(Target.serializer(), element) }.getOrNull()

/** ERROR 级问题阻止保存；拼成一行返回，无问题返回 null。 */
private suspend fun problemsOf(
    validation: FlowValidationService,
    definition: FlowDefinition,
): String? =
    validation
        .validate(definition)
        .filter { it.severity == IssueSeverity.ERROR }
        .joinToString("；") { it.message }
        .ifBlank { null }

private fun findNode(
    steps: List<Node>,
    nodeId: String,
): Node? {
    for (node in steps) {
        if (node.id == nodeId) return node
        childrenOf(node).forEach { child -> findNode(listOf(child), nodeId)?.let { return it } }
    }
    return null
}

private fun replaceNode(
    steps: List<Node>,
    nodeId: String,
    replacement: Node,
): List<Node> =
    steps.map { node ->
        if (node.id == nodeId) {
            replacement
        } else {
            when (node) {
                is IfNode ->
                    node.copy(
                        thenSteps = replaceNode(node.thenSteps, nodeId, replacement),
                        elseSteps = replaceNode(node.elseSteps, nodeId, replacement),
                    )
                is SwitchNode ->
                    node.copy(
                        cases = node.cases.map { it.copy(steps = replaceNode(it.steps, nodeId, replacement)) },
                        default = replaceNode(node.default, nodeId, replacement),
                    )
                is LoopNode -> node.copy(steps = replaceNode(node.steps, nodeId, replacement))
                else -> node
            }
        }
    }

private fun removeNode(
    steps: List<Node>,
    nodeId: String,
): List<Node> =
    steps.filter { it.id != nodeId }.map { node ->
        when (node) {
            is IfNode ->
                node.copy(
                    thenSteps = removeNode(node.thenSteps, nodeId),
                    elseSteps = removeNode(node.elseSteps, nodeId),
                )
            is SwitchNode ->
                node.copy(
                    cases = node.cases.map { it.copy(steps = removeNode(it.steps, nodeId)) },
                    default = removeNode(node.default, nodeId),
                )
            is LoopNode -> node.copy(steps = removeNode(node.steps, nodeId))
            else -> node
        }
    }

private fun childrenOf(node: Node): List<Node> =
    when (node) {
        is IfNode -> node.thenSteps + node.elseSteps
        is SwitchNode -> node.cases.flatMap { it.steps } + node.default
        is LoopNode -> node.steps
        else -> emptyList()
    } + node.onFail?.steps.orEmpty()

/** 新节点 id：`n<已用数+1>` 起找第一个未占用的。 */
private fun nextId(steps: List<Node>): String {
    val used = mutableSetOf<String>()
    collectIds(steps, used)
    var candidate = used.size + 1
    while ("n$candidate" in used) candidate++
    return "n$candidate"
}

private fun collectIds(
    steps: List<Node>,
    into: MutableSet<String>,
) {
    for (node in steps) {
        into += node.id
        collectIds(childrenOf(node), into)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun invalid(message: String): Outcome<Nothing> =
    Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = message))
