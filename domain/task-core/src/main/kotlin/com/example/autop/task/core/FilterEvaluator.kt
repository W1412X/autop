/**
 * 触发事件的规则过滤（docs/tech/07 §2）：对 [FilterGroup] 求值；
 * 字段缺失时 EXISTS=false、其他操作视为不匹配；REGEX 编译结果缓存，
 * 编译失败视为不匹配并记录。
 */
package com.example.autop.task.core

import com.example.autop.task.FilterClause
import com.example.autop.task.FilterGroup
import com.example.autop.task.FilterOp
import com.example.autop.task.GroupMode
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

/** 过滤求值（纯函数对象，正则缓存线程安全）。 */
public object FilterEvaluator {
    private val regexCache = ConcurrentHashMap<String, Regex?>()
    private val brokenRegex = ConcurrentHashMap.newKeySet<String>()

    /** 已知编译失败的正则（供诊断展示）。 */
    public fun brokenPatterns(): Set<String> = brokenRegex.toSet()

    /** 对 [group] 求值。 */
    public fun matches(
        group: FilterGroup,
        fields: Map<String, String>,
    ): Boolean {
        val clauseResults = group.clauses.all { matches(it, fields) }
        val groupResults = group.groups.all { matches(it, fields) }
        val all = clauseResults && groupResults
        return when (group.mode) {
            GroupMode.ALL -> all
            GroupMode.ANY ->
                group.clauses.any { matches(it, fields) } ||
                    group.groups.any { matches(it, fields) }
        }
    }

    private fun matches(
        clause: FilterClause,
        fields: Map<String, String>,
    ): Boolean {
        val value = fields[clause.field]
        if (value == null) {
            // 字段缺失：EXISTS=false，其他操作视为不匹配。
            return false
        }
        val actual = if (clause.ignoreCase) value.lowercase() else value
        val expected = if (clause.ignoreCase) clause.value.lowercase() else clause.value
        return when (clause.op) {
            FilterOp.EQUALS -> actual == expected
            FilterOp.NOT_EQUALS -> actual != expected
            FilterOp.CONTAINS -> actual.contains(expected)
            FilterOp.NOT_CONTAINS -> !actual.contains(expected)
            FilterOp.STARTS_WITH -> actual.startsWith(expected)
            FilterOp.REGEX -> regex(clause)?.containsMatchIn(value) == true
            FilterOp.IN -> expected.split(IN_SEPARATOR).any { it.trim() == actual }
            FilterOp.EXISTS -> true
        }
    }

    /** 编译失败返回 null 并记录（视为不匹配）。 */
    @Suppress("SwallowedException") // 编译失败即不匹配，异常本身不携带额外信息；坏正则记入 brokenPatterns
    private fun regex(clause: FilterClause): Regex? {
        val key = clause.value
        return regexCache.computeIfAbsent(key) {
            try {
                Regex(key, if (clause.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } catch (e: PatternSyntaxException) {
                brokenRegex += key
                null
            }
        }
    }

    private const val IN_SEPARATOR = "|"
}
