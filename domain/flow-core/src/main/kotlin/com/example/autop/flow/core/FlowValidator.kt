@file:Suppress("CyclomaticComplexMethod", "ComplexCondition") // 按节点类型/条件类型逐个校验的分发表

package com.example.autop.flow.core

import com.example.autop.agent.ToolAccess
import com.example.autop.flow.AgentNode
import com.example.autop.flow.ApiNode
import com.example.autop.flow.BreakNode
import com.example.autop.flow.CallNode
import com.example.autop.flow.Condition
import com.example.autop.flow.ContinueNode
import com.example.autop.flow.EndNode
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.FlowSpec
import com.example.autop.flow.IfNode
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.LlmNode
import com.example.autop.flow.LlmOutputType
import com.example.autop.flow.LogNode
import com.example.autop.flow.LoopMode
import com.example.autop.flow.LoopNode
import com.example.autop.flow.Node
import com.example.autop.flow.ObserveNode
import com.example.autop.flow.OnFail
import com.example.autop.flow.PrePolicy
import com.example.autop.flow.Precondition
import com.example.autop.flow.ReadFrom
import com.example.autop.flow.ReadNode
import com.example.autop.flow.SetNode
import com.example.autop.flow.StateNode
import com.example.autop.flow.StateOp
import com.example.autop.flow.SwitchNode
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.ToolNode
import com.example.autop.flow.WaitFor
import com.example.autop.flow.WaitNode
import com.example.autop.flow.core.expr.Expr
import com.example.autop.flow.core.expr.ExprParser
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.expr.Templates

/** 校验所需的外部信息（由 flow-impl 预加载提供）。 */
public interface FlowCatalog {
    /** 工具访问类别；工具不存在返回 null。 */
    public fun toolAccess(name: String): ToolAccess?

    /** 按名称或 id 取流程；不存在返回 null。 */
    public fun flow(nameOrId: String): FlowDefinition?

    public fun endpointRegistered(name: String): Boolean
}

/** 流程静态校验（docs/design/flows.md §12.3）。错误阻止保存，警告不阻止。 */
public class FlowValidator(
    private val catalog: FlowCatalog,
) {
    private val issues = mutableListOf<FlowIssue>()
    private val ids = HashSet<String>()

    /** 全流程的节点 id（含分支、循环、onFail 兜底），用于引用完整性。 */
    private var allNodeIds: Set<String> = emptySet()

    /** 按执行顺序已经处理过的节点 id，用于拦截"引用更晚的节点"。 */
    private val processedIds = HashSet<String>()

    public fun validate(flow: FlowDefinition): List<FlowIssue> {
        issues.clear()
        ids.clear()
        processedIds.clear()
        allNodeIds = collectIds(flow.steps)
        if (flow.format != FlowSpec.FORMAT) error(null, FlowErrorCodes.INVALID, "format 必须是 ${FlowSpec.FORMAT}")
        if (flow.schemaVersion > FlowSpec.CURRENT_SCHEMA_VERSION) {
            error(null, FlowErrorCodes.INVALID, "流程版本 ${flow.schemaVersion} 高于当前支持的 ${FlowSpec.CURRENT_SCHEMA_VERSION}")
        }
        if (flow.id.isBlank()) error(null, FlowErrorCodes.INVALID, "缺少流程 id")
        if (flow.name.isBlank()) error(null, FlowErrorCodes.INVALID, "缺少流程名称")
        val defined = (flow.params.map { it.name } + RESERVED).toSet()
        flow.settings.onFail?.let { onFail(null, it, defined) }
        steps(flow.steps, defined, inLoop = false)
        return issues.toList()
    }

    private fun error(
        nodeId: String?,
        code: String,
        message: String,
    ) {
        issues += FlowIssue(IssueSeverity.ERROR, nodeId, code, message)
    }

    private fun warn(
        nodeId: String?,
        code: String,
        message: String,
    ) {
        issues += FlowIssue(IssueSeverity.WARNING, nodeId, code, message)
    }

    /** 校验一组步骤；返回其结束时一定已定义的变量。 */
    private fun steps(
        list: List<Node>,
        start: Set<String>,
        inLoop: Boolean,
    ): Set<String> {
        var defined = start
        for (n in list) defined = node(n, defined, inLoop)
        return defined
    }

    private fun node(
        n: Node,
        defined: Set<String>,
        inLoop: Boolean,
    ): Set<String> {
        if (n.id.isBlank()) {
            error(null, FlowErrorCodes.INVALID, "存在没有 id 的节点")
        } else if (!ids.add(n.id)) {
            error(n.id, FlowErrorCodes.INVALID, "节点 id 重复：${n.id}")
        }
        processedIds.add(n.id)
        n.pre?.let { pre(n.id, it, defined) }
        n.onFail?.let { onFail(n.id, it, defined) }
        return when (n) {
            is ToolNode -> tool(n, defined)
            is ObserveNode -> out(n.out, defined)
            is ReadNode -> read(n, defined)
            is WaitNode -> wait(n, defined)
            is LlmNode -> llm(n, defined)
            is IfNode -> branch(n, defined, inLoop)
            is SwitchNode -> switch(n, defined, inLoop)
            is LoopNode -> loop(n, defined)
            is BreakNode, is ContinueNode -> {
                if (!inLoop) error(n.id, FlowErrorCodes.INVALID, "break / continue 只能在循环内使用")
                defined
            }
            is CallNode -> call(n, defined)
            is AgentNode -> {
                template(n.id, n.goal, defined)
                out(n.out, defined)
            }
            is ApiNode -> api(n, defined)
            is SetNode -> set(n, defined)
            is StateNode -> state(n, defined)
            is LogNode -> {
                template(n.id, n.message, defined)
                defined
            }
            is EndNode -> {
                n.outputs.values.forEach { expr(n.id, it, defined) }
                defined
            }
        }
    }

    private fun out(
        name: String?,
        defined: Set<String>,
    ): Set<String> =
        if (name.isNullOrBlank()) {
            defined
        } else {
            defined +
                name
        }

    private fun onFail(
        nodeId: String?,
        o: OnFail,
        defined: Set<String>,
    ) {
        o.handoff?.let { template(nodeId, it.goal, defined + "last") }
        steps(o.steps, defined, inLoop = false)
    }

    /** 校验前置条件（docs/design/flows-precondition-selfrepair.md P4）。 */
    private fun pre(
        nodeId: String,
        p: Precondition,
        defined: Set<String>,
    ) {
        if (p.policy == PrePolicy.WAIT && (p.timeoutMs ?: 0) < 0) {
            error(nodeId, FlowErrorCodes.INVALID, "pre 的 timeoutMs 不能为负")
        }
        p.message?.let { template(nodeId, it, defined) }
        cond(nodeId, p.cond, defined)
        preCondRules(nodeId, p.cond)
        if (findLlm(p.cond) != null) {
            error(nodeId, FlowErrorCodes.INVALID, "前置条件不支持 llm 判定：每个节点都会求值，会重复消耗模型额度")
        }
        checkPreRefs(nodeId, p.cond)
    }

    /**
     * 前置条件里的必填项：页面文字必须有非空内容，元素条件必须有目标。
     * 与 `expect` 的同类规则一致（见 `expectRules`）。
     */
    private fun preCondRules(
        nodeId: String,
        c: Condition,
    ) {
        when (c) {
            is Condition.ScreenText ->
                if (c.value.isBlank()) error(nodeId, FlowErrorCodes.INVALID, "pre 的 screenText 条件缺少内容")
            is Condition.Element ->
                if (c.target.value.isNullOrBlank() && c.target.tag.isNullOrBlank()) {
                    error(nodeId, FlowErrorCodes.INVALID, "pre 的 element 条件缺少目标")
                }
            is Condition.All -> c.of.forEach { preCondRules(nodeId, it) }
            is Condition.Any -> c.of.forEach { preCondRules(nodeId, it) }
            is Condition.Not -> preCondRules(nodeId, c.of)
            else -> Unit
        }
    }

    /** 前置条件的节点引用必须指向已执行的节点，不能依赖尚未执行的结果。 */
    private fun checkPreRefs(
        nodeId: String,
        c: Condition,
    ) {
        when (c) {
            is Condition.LastOk -> {
                val target = c.node
                when {
                    target !in allNodeIds -> error(nodeId, FlowErrorCodes.NOT_FOUND, "pre 引用了不存在的节点：$target")
                    target !in processedIds -> error(nodeId, FlowErrorCodes.INVALID, "pre 引用了尚未执行的节点：$target")
                }
            }
            is Condition.ScreenChanged -> {
                val since = c.since
                if (since != "lastAction" && since !in allNodeIds) {
                    error(nodeId, FlowErrorCodes.NOT_FOUND, "pre 的 screenChanged.since 引用了不存在的节点：$since")
                }
            }
            is Condition.All -> c.of.forEach { checkPreRefs(nodeId, it) }
            is Condition.Any -> c.of.forEach { checkPreRefs(nodeId, it) }
            is Condition.Not -> checkPreRefs(nodeId, c.of)
            else -> Unit
        }
    }

    /** 找出条件树里的第一个 LLM 判定。 */
    private fun findLlm(c: Condition): Condition.Llm? =
        when (c) {
            is Condition.Llm -> c
            is Condition.All -> c.of.firstNotNullOfOrNull { findLlm(it) }
            is Condition.Any -> c.of.firstNotNullOfOrNull { findLlm(it) }
            is Condition.Not -> findLlm(c.of)
            else -> null
        }

    /** 递归收集步骤树（含分支、循环、onFail 兜底）里的全部节点 id。 */
    private fun collectIds(steps: List<Node>): Set<String> {
        val out = HashSet<String>()

        fun walk(list: List<Node>) {
            for (n in list) {
                out += n.id
                n.onFail?.let { walk(it.steps) }
                when (n) {
                    is IfNode -> {
                        walk(n.thenSteps)
                        walk(n.elseSteps)
                    }
                    is SwitchNode -> {
                        n.cases.forEach { walk(it.steps) }
                        walk(n.default)
                    }
                    is LoopNode -> walk(n.steps)
                    else -> Unit
                }
            }
        }

        walk(steps)
        return out
    }

    private fun tool(
        n: ToolNode,
        defined: Set<String>,
    ): Set<String> {
        if (n.tool in FlowToolRules.FORBIDDEN) {
            error(n.id, FlowErrorCodes.TOOL_FORBIDDEN, "流程不能使用工具 ${n.tool}")
        } else if (catalog.toolAccess(n.tool) == null) {
            error(n.id, FlowErrorCodes.NOT_FOUND, "工具不存在或未启用：${n.tool}")
        }
        n.args.values.forEach { templatesIn(n.id, it.toString(), defined) }
        n.target?.let { target(n.id, it, defined) }
        n.target2?.let { target(n.id, it, defined) }
        n.expect?.let { expectRules(n.id, it, defined) }
        n.anchor?.target?.let { target(n.id, it, defined) }
        return out(n.out, defined)
    }

    /** 期望效果的必填项：文字类需要文字，元素类需要目标，应用类需要包名。 */
    private fun expectRules(
        id: String,
        e: com.example.autop.flow.Expect,
        defined: Set<String>,
    ) {
        when (e.kind) {
            com.example.autop.flow.ExpectKind.TEXT, com.example.autop.flow.ExpectKind.TEXT_GONE ->
                if (e.text.isNullOrBlank()) error(id, FlowErrorCodes.INVALID, "expect 需要 text")
            com.example.autop.flow.ExpectKind.ELEMENT, com.example.autop.flow.ExpectKind.ELEMENT_GONE ->
                if (e.target == null) error(id, FlowErrorCodes.INVALID, "expect 需要 target")
            com.example.autop.flow.ExpectKind.APP ->
                if (e.app.isNullOrBlank()) error(id, FlowErrorCodes.INVALID, "expect 需要 app")
            com.example.autop.flow.ExpectKind.NONE, com.example.autop.flow.ExpectKind.SCREEN_CHANGED -> Unit
        }
        e.target?.let { target(id, it, defined) }
    }

    private fun read(
        n: ReadNode,
        defined: Set<String>,
    ): Set<String> {
        if ((n.from == ReadFrom.TARGET || n.from == ReadFrom.LIST) && n.target == null) {
            error(n.id, FlowErrorCodes.INVALID, "read 需要 target")
        }
        if (n.from == ReadFrom.REGION && n.region == null) error(n.id, FlowErrorCodes.INVALID, "region 读取需要 region")
        n.target?.let { target(n.id, it, defined) }
        return out(n.out, defined)
    }

    private fun wait(
        n: WaitNode,
        defined: Set<String>,
    ): Set<String> {
        when (n.waitFor) {
            WaitFor.TIME -> if (n.ms == null) error(n.id, FlowErrorCodes.INVALID, "wait(time) 需要 ms")
            WaitFor.TEXT, WaitFor.GONE, WaitFor.APP ->
                if (n.value ==
                    null
                ) {
                    error(n.id, FlowErrorCodes.INVALID, "wait 需要 value")
                }
            WaitFor.ELEMENT, WaitFor.ELEMENT_GONE ->
                if (n.target ==
                    null
                ) {
                    error(n.id, FlowErrorCodes.INVALID, "wait 需要 target")
                }
            WaitFor.COND -> if (n.cond == null) error(n.id, FlowErrorCodes.INVALID, "wait(cond) 需要 cond")
            WaitFor.UNTIL ->
                if (n.value ==
                    null
                ) {
                    error(n.id, FlowErrorCodes.INVALID, "wait(until) 需要表达式 value")
                } else {
                    expr(n.id, n.value!!, defined)
                }
            WaitFor.CHANGE, WaitFor.STABLE, WaitFor.NOTIFICATION -> Unit
        }
        n.target?.let { target(n.id, it, defined) }
        n.cond?.let { cond(n.id, it, defined) }
        return out(n.out, defined)
    }

    private fun llm(
        n: LlmNode,
        defined: Set<String>,
    ): Set<String> {
        template(n.id, n.prompt, defined)
        n.system?.let { template(n.id, it, defined) }
        when (n.output.type) {
            LlmOutputType.LABEL ->
                if (n.output.labels.isNullOrEmpty()) {
                    error(
                        n.id,
                        FlowErrorCodes.INVALID,
                        "label 输出需要 labels",
                    )
                }
            LlmOutputType.JSON -> if (n.output.schema == null) warn(n.id, FlowErrorCodes.INVALID, "json 输出建议提供 schema")
            else -> Unit
        }
        n.onInvalid?.let { expr(n.id, it.use, defined) }
        return out(n.out, defined)
    }

    private fun branch(
        n: IfNode,
        defined: Set<String>,
        inLoop: Boolean,
    ): Set<String> {
        cond(n.id, n.cond, defined)
        val a = steps(n.thenSteps, defined, inLoop)
        val b = steps(n.elseSteps, defined, inLoop)
        return a.intersect(b) + defined
    }

    private fun switch(
        n: SwitchNode,
        defined: Set<String>,
        inLoop: Boolean,
    ): Set<String> {
        expr(n.id, n.on, defined)
        val results = n.cases.map { steps(it.steps, defined, inLoop) } + listOf(steps(n.default, defined, inLoop))
        return results.reduce { x, y -> x.intersect(y) } + defined
    }

    private fun loop(
        n: LoopNode,
        defined: Set<String>,
    ): Set<String> {
        when (n.mode) {
            LoopMode.COUNT ->
                n.count?.let { expr(n.id, it, defined) }
                    ?: error(n.id, FlowErrorCodes.INVALID, "count 循环需要 count")
            LoopMode.EACH ->
                n.items?.let { expr(n.id, it, defined) }
                    ?: error(n.id, FlowErrorCodes.INVALID, "each 循环需要 items")
            LoopMode.WHILE, LoopMode.UNTIL ->
                n.cond?.let { cond(n.id, it, defined) }
                    ?: error(n.id, FlowErrorCodes.INVALID, "该循环需要 cond")
            LoopMode.FOREVER -> Unit
        }
        steps(n.steps, defined + n.indexVar + n.itemVar, inLoop = true)
        return defined
    }

    private fun call(
        n: CallNode,
        defined: Set<String>,
    ): Set<String> {
        template(n.id, n.flow, defined)
        if (!n.flow.contains("{{") &&
            catalog.flow(n.flow) == null
        ) {
            error(n.id, FlowErrorCodes.NOT_FOUND, "子流程不存在：${n.flow}")
        }
        n.args.values.forEach { templatesIn(n.id, it.toString(), defined) }
        return out(n.out, defined)
    }

    private fun api(
        n: ApiNode,
        defined: Set<String>,
    ): Set<String> {
        if (!n.endpoint.contains("{{") && !catalog.endpointRegistered(n.endpoint)) {
            warn(n.id, FlowErrorCodes.ENDPOINT_UNREGISTERED, "接口未登记：${n.endpoint}")
        }
        template(n.id, n.path, defined)
        n.body?.let { templatesIn(n.id, it.toString(), defined) }
        return out(n.out, defined)
    }

    private fun set(
        n: SetNode,
        defined: Set<String>,
    ): Set<String> {
        var d = defined
        for ((name, e) in n.vars) {
            if (!NAME.matches(name) || name in RESERVED) error(n.id, FlowErrorCodes.INVALID, "变量名不合法：$name")
            expr(n.id, e, d)
            d = d + name
        }
        return d
    }

    private fun state(
        n: StateNode,
        defined: Set<String>,
    ): Set<String> {
        template(n.id, n.key, defined)
        n.value?.let { expr(n.id, it, defined) }
        n.default?.let { expr(n.id, it, defined) }
        if (n.op == StateOp.SET && n.value == null) error(n.id, FlowErrorCodes.INVALID, "state.set 需要 value")
        return if (n.op == StateOp.DELETE) defined else out(n.out, defined)
    }

    private fun target(
        nodeId: String,
        t: Target,
        defined: Set<String>,
    ) {
        if (t.by != TargetBy.TAG && t.value == null && t.tag == null && t.fallback == null) {
            error(nodeId, FlowErrorCodes.INVALID, "目标描述缺少 value / tag")
        }
        t.value?.let { template(nodeId, it, defined) }
        t.region?.let { if (it.size != REGION) error(nodeId, FlowErrorCodes.INVALID, "region 需要 4 个数") }
        t.within?.let { target(nodeId, it, defined) }
    }

    private fun cond(
        nodeId: String,
        c: Condition,
        defined: Set<String>,
    ) {
        when (c) {
            is Condition.Expr -> expr(nodeId, c.expr, defined)
            is Condition.ScreenText -> template(nodeId, c.value, defined)
            is Condition.Element -> target(nodeId, c.target, defined)
            is Condition.App -> template(nodeId, c.value, defined)
            is Condition.Llm -> template(nodeId, c.prompt, defined)
            is Condition.All -> c.of.forEach { cond(nodeId, it, defined) }
            is Condition.Any -> c.of.forEach { cond(nodeId, it, defined) }
            is Condition.Not -> cond(nodeId, c.of, defined)
            is Condition.ScreenChanged,
            is Condition.Ime,
            is Condition.Device,
            is Condition.TimeWindow,
            is Condition.LastOk,
            -> Unit
        }
    }

    private fun templatesIn(
        nodeId: String,
        raw: String,
        defined: Set<String>,
    ) = template(nodeId, raw, defined)

    private fun template(
        nodeId: String?,
        raw: String,
        defined: Set<String>,
    ) {
        val exprs =
            try {
                Templates.expressions(raw)
            } catch (e: FlowException) {
                error(nodeId, e.code, e.message.orEmpty())
                return
            }
        exprs.forEach { expr(nodeId, it, defined) }
    }

    private fun expr(
        nodeId: String?,
        source: String,
        defined: Set<String>,
    ) {
        val ast =
            try {
                ExprParser.parse(source)
            } catch (e: FlowException) {
                error(nodeId, e.code, e.message.orEmpty())
                return
            }
        refs(ast).filter { it !in defined }.forEach {
            warn(nodeId, FlowErrorCodes.VAR_UNDEFINED, "变量 $it 可能在使用前未定义")
        }
    }

    private fun refs(e: Expr): Set<String> =
        when (e) {
            is Expr.Ref -> setOf(e.name)
            is Expr.Lit -> emptySet()
            is Expr.Field -> refs(e.target)
            is Expr.Index -> refs(e.target) + refs(e.index)
            is Expr.Unary -> refs(e.operand)
            is Expr.Binary -> refs(e.left) + refs(e.right)
            is Expr.Call -> e.args.flatMap { refs(it) }.toSet()
            is Expr.ListLit -> e.items.flatMap { refs(it) }.toSet()
            is Expr.ObjectLit -> e.entries.flatMap { refs(it.second) }.toSet()
        }

    private companion object {
        val RESERVED = setOf("param", "trigger", "run", "last", "out", "error")
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val REGION = 4
    }
}

/** 判断流程是否需要屏幕（docs/design/flows.md §9.4）。 */
public object FlowAnalyzer {
    private val SCREEN_WAITS =
        setOf(
            WaitFor.TEXT,
            WaitFor.GONE,
            WaitFor.ELEMENT,
            WaitFor.ELEMENT_GONE,
            WaitFor.CHANGE,
            WaitFor.STABLE,
            WaitFor.APP,
        )

    public fun needsScreen(
        flow: FlowDefinition,
        catalog: FlowCatalog,
        screenTools: (String) -> Boolean,
    ): Boolean = needs(flow, catalog, screenTools, HashSet())

    private fun needs(
        flow: FlowDefinition,
        catalog: FlowCatalog,
        screenTools: (String) -> Boolean,
        visiting: MutableSet<String>,
    ): Boolean {
        if (!visiting.add(flow.id)) return false
        val all =
            flow.steps + (
                flow.settings.onFail
                    ?.steps
                    .orEmpty()
            )
        return all.any { nodeNeeds(it, catalog, screenTools, visiting) }
    }

    private fun nodeNeeds(
        n: Node,
        catalog: FlowCatalog,
        screenTools: (String) -> Boolean,
        visiting: MutableSet<String>,
    ): Boolean {
        val own =
            when (n) {
                is ObserveNode, is ReadNode -> true
                is ToolNode -> n.target != null || n.target2 != null || screenTools(n.tool)
                is WaitNode -> n.waitFor in SCREEN_WAITS || n.target != null || (n.cond?.let(::condNeeds) ?: false)
                is IfNode -> condNeeds(n.cond)
                is LoopNode -> n.cond?.let(::condNeeds) ?: false
                is AgentNode -> n.tools == null || n.tools!!.any(screenTools)
                is LlmNode -> n.screen
                is CallNode -> catalog.flow(n.flow)?.let { needs(it, catalog, screenTools, visiting) } ?: false
                else -> false
            }
        if (own) return true
        val children =
            when (n) {
                is IfNode -> n.thenSteps + n.elseSteps
                is SwitchNode -> n.cases.flatMap { it.steps } + n.default
                is LoopNode -> n.steps
                else -> emptyList()
            }
        val recovery = n.onFail?.steps.orEmpty()
        val handoff = n.onFail?.handoff != null
        return children.any { nodeNeeds(it, catalog, screenTools, visiting) } ||
            recovery.any { nodeNeeds(it, catalog, screenTools, visiting) } ||
            handoff
    }

    private fun condNeeds(c: Condition): Boolean =
        when (c) {
            is Condition.ScreenText,
            is Condition.Element,
            is Condition.App,
            is Condition.ScreenChanged,
            is Condition.Ime,
            -> true
            is Condition.Llm -> c.screen
            is Condition.All -> c.of.any(::condNeeds)
            is Condition.Any -> c.of.any(::condNeeds)
            is Condition.Not -> condNeeds(c.of)
            is Condition.Expr, is Condition.Device, is Condition.TimeWindow, is Condition.LastOk -> false
        }
}
