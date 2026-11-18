package com.example.autop.task.core

import com.example.autop.task.FilterClause
import com.example.autop.task.FilterGroup
import com.example.autop.task.FilterOp
import com.example.autop.task.GroupMode

/**
 * 条件触发过滤条件的静态校验（工具参数与创作草稿共用）。
 * 只检查结构与字段归属，不执行过滤。
 */
public object FilterValidator {
    /** 组内是否有任何条件（递归）。没有条件的 ALL 组匹配全部事件，由调用方决定是否允许。 */
    public fun hasRules(group: FilterGroup): Boolean = group.clauses.isNotEmpty() || group.groups.any { hasRules(it) }

    /**
     * 返回给模型看的问题列表（中文）；空列表表示通过。
     * 字段必须属于事件源（[fieldNames]），正则必须能编译，嵌套组不能为空，ANY 组必须有条件。
     */
    public fun problems(
        group: FilterGroup,
        fieldNames: Collection<String>,
    ): List<String> {
        val known = fieldNames.toSet()
        val out = mutableListOf<String>()
        collect(group, known, path = "filter", nested = false, out = out)
        return out
    }

    private fun collect(
        group: FilterGroup,
        known: Set<String>,
        path: String,
        nested: Boolean,
        out: MutableList<String>,
    ) {
        if (nested && !hasRules(group)) out += "$path 是空的条件组"
        if (group.mode == GroupMode.ANY && !hasRules(group)) out += "$path 为 ANY 但没有任何条件，永远不会匹配"
        group.clauses.forEachIndexed { i, clause -> clauseProblems(clause, known, "$path.clauses[$i]", out) }
        group.groups.forEachIndexed { i, child -> collect(child, known, "$path.groups[$i]", nested = true, out = out) }
    }

    private fun clauseProblems(
        clause: FilterClause,
        known: Set<String>,
        path: String,
        out: MutableList<String>,
    ) {
        if (clause.field !in known) {
            val available = known.sorted().joinToString("、").ifEmpty { "（该源没有字段）" }
            out += "$path 的字段「${clause.field}」不属于该事件源，可用字段：$available"
        }
        if (clause.op != FilterOp.EXISTS && clause.value.isBlank()) out += "$path 的 value 不能为空"
        if (clause.op == FilterOp.REGEX && runCatching { Regex(clause.value) }.isFailure) {
            out += "$path 的正则无法编译：${clause.value}"
        }
    }
}
