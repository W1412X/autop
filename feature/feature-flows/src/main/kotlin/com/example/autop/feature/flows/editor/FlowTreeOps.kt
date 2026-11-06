/**
 * 步骤树的纯函数编辑操作（编辑器用）：按节点 id 定位，新增 / 删除 / 移动 / 复制 / 替换。
 * 节点内字段的修改通过 JSON 往返完成（见 [NodeJson]）。
 */
package com.example.autop.feature.flows.editor

import com.example.autop.core.common.AutopJson
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowTree
import com.example.autop.flow.IfNode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.SwitchCase
import com.example.autop.flow.SwitchNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 节点与 JSON 对象互转。 */
internal object NodeJson {
    private val json = AutopJson.default

    fun encode(node: Node): JsonObject = json.encodeToJsonElement(Node.serializer(), node) as JsonObject

    /** 解码失败抛 [IllegalArgumentException]（kotlinx 的 SerializationException 继承自它）。 */
    fun decode(obj: JsonObject): Node = json.decodeFromJsonElement(Node.serializer(), obj)
}

/** 容器节点的一个子步骤列表位置。 */
public data class ListSlot(
    /** 父节点 id；null 表示流程顶层。 */
    val parentId: String?,
    /** `then` / `else` / `body` / `case<i>` / `default`；顶层为 `root`。 */
    val key: String,
    /** 非 null 时表示插入到该节点之后（忽略 [parentId] / [key]）。 */
    val afterId: String? = null,
)

internal object FlowTreeOps {
    /** 下一个不冲突的节点 id（`n<数字>`）。 */
    fun nextId(flow: FlowDefinition): String {
        val used = FlowTree.allNodes(flow).map { it.id }.toSet()
        var i = used.mapNotNull { it.removePrefix("n").toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        while ("n$i" in used) i++
        return "n$i"
    }

    fun find(
        flow: FlowDefinition,
        id: String,
    ): Node? = FlowTree.allNodes(flow).firstOrNull { it.id == id }

    /** 替换节点（保持位置）。 */
    fun replace(
        flow: FlowDefinition,
        id: String,
        node: Node,
    ): FlowDefinition = flow.copy(steps = mapList(flow.steps) { list -> list.map { if (it.id == id) node else it } })

    fun remove(
        flow: FlowDefinition,
        id: String,
    ): FlowDefinition = flow.copy(steps = mapList(flow.steps) { list -> list.filterNot { it.id == id } })

    /** 同一列表内上移（-1）/下移（+1）。 */
    fun move(
        flow: FlowDefinition,
        id: String,
        delta: Int,
    ): FlowDefinition =
        flow.copy(
            steps =
                mapList(flow.steps) { list ->
                    val i = list.indexOfFirst { it.id == id }
                    val j = i + delta
                    if (i < 0 || j !in list.indices) {
                        list
                    } else {
                        list.toMutableList().apply { add(j, removeAt(i)) }
                    }
                },
        )

    fun insertAfter(
        flow: FlowDefinition,
        afterId: String,
        node: Node,
    ): FlowDefinition =
        flow.copy(
            steps =
                mapList(flow.steps) { list ->
                    val i = list.indexOfFirst { it.id == afterId }
                    if (i < 0) list else list.toMutableList().apply { add(i + 1, node) }
                },
        )

    /** 追加到某个列表末尾。 */
    fun append(
        flow: FlowDefinition,
        slot: ListSlot,
        node: Node,
    ): FlowDefinition {
        if (slot.parentId == null) return flow.copy(steps = flow.steps + node)
        return flow.copy(
            steps =
                mapNodes(flow.steps) { n ->
                    if (n.id ==
                        slot.parentId
                    ) {
                        appendChild(n, slot.key, node)
                    } else {
                        n
                    }
                },
        )
    }

    /** 深拷贝一个节点并给整棵子树换新 id。 */
    fun duplicate(
        flow: FlowDefinition,
        id: String,
    ): Node? {
        val source = find(flow, id) ?: return null
        var counter = 0
        val used = FlowTree.allNodes(flow).map { it.id }.toMutableSet()

        fun fresh(): String {
            var i = used.mapNotNull { it.removePrefix("n").toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
            i += counter
            counter++
            return "n$i".also { used += it }
        }

        fun reId(node: Node): Node {
            val obj = NodeJson.encode(node).toMutableMap()
            obj["id"] = JsonPrimitive(fresh())
            val base = NodeJson.decode(JsonObject(obj))
            return mapChildren(base, ::reId)
        }
        return reId(source)
    }

    // ---- 内部 ---------------------------------------------------------------------------------

    private fun appendChild(
        node: Node,
        key: String,
        child: Node,
    ): Node =
        when (node) {
            is IfNode ->
                if (key ==
                    "then"
                ) {
                    node.copy(thenSteps = node.thenSteps + child)
                } else {
                    node.copy(elseSteps = node.elseSteps + child)
                }
            is LoopNode -> node.copy(steps = node.steps + child)
            is SwitchNode ->
                if (key == "default") {
                    node.copy(default = node.default + child)
                } else {
                    val index = key.removePrefix("case").toIntOrNull()?.minus(1) ?: -1
                    node.copy(
                        cases =
                            node.cases.mapIndexed { i, c ->
                                if (i ==
                                    index
                                ) {
                                    c.copy(steps = c.steps + child)
                                } else {
                                    c
                                }
                            },
                    )
                }
            else -> node
        }

    /** 对每个步骤列表（含嵌套）应用 [f]，自底向上。 */
    private fun mapList(
        steps: List<Node>,
        f: (List<Node>) -> List<Node>,
    ): List<Node> = f(steps).map { mapChildLists(it, f) }

    private fun mapChildLists(
        node: Node,
        f: (List<Node>) -> List<Node>,
    ): Node =
        when (node) {
            is IfNode -> node.copy(thenSteps = mapList(node.thenSteps, f), elseSteps = mapList(node.elseSteps, f))
            is LoopNode -> node.copy(steps = mapList(node.steps, f))
            is SwitchNode ->
                node.copy(
                    cases = node.cases.map { SwitchCase(it.values, mapList(it.steps, f)) },
                    default = mapList(node.default, f),
                )
            else -> node
        }

    private fun mapNodes(
        steps: List<Node>,
        f: (Node) -> Node,
    ): List<Node> = steps.map { mapChildren(f(it)) { child -> f(child) } }

    private fun mapChildren(
        node: Node,
        f: (Node) -> Node,
    ): Node =
        when (node) {
            is IfNode ->
                node.copy(
                    thenSteps =
                        node.thenSteps.map {
                            mapChildren(f(it), f)
                        },
                    elseSteps = node.elseSteps.map { mapChildren(f(it), f) },
                )
            is LoopNode -> node.copy(steps = node.steps.map { mapChildren(f(it), f) })
            is SwitchNode ->
                node.copy(
                    cases = node.cases.map { SwitchCase(it.values, it.steps.map { s -> mapChildren(f(s), f) }) },
                    default = node.default.map { mapChildren(f(it), f) },
                )
            else -> node
        }

    /** 新建节点的默认 JSON（按类型）。 */
    fun newNode(
        type: String,
        id: String,
    ): Node {
        val base = mutableMapOf<String, JsonElement>("type" to JsonPrimitive(type), "id" to JsonPrimitive(id))
        when (type) {
            "tool" -> base["tool"] = JsonPrimitive("tap")
            "wait" -> base["for"] = JsonPrimitive("time")
            "if" -> base["cond"] = JsonObject(mapOf("kind" to JsonPrimitive("expr"), "expr" to JsonPrimitive("true")))
            "loop" -> {
                base["mode"] = JsonPrimitive("count")
                base["count"] = JsonPrimitive("3")
            }
            "llm" -> base["prompt"] = JsonPrimitive("")
            "read" -> base["from"] = JsonPrimitive("page")
            "set" -> base["vars"] = JsonObject(emptyMap())
            "state" -> {
                base["op"] = JsonPrimitive("get")
                base["key"] = JsonPrimitive("")
            }
            "call" -> base["flow"] = JsonPrimitive("")
            "agent" -> base["goal"] = JsonPrimitive("")
            "api" -> base["endpoint"] = JsonPrimitive("")
            "switch" -> base["on"] = JsonPrimitive("")
            "log" -> base["message"] = JsonPrimitive("")
        }
        return NodeJson.decode(JsonObject(base))
    }
}
