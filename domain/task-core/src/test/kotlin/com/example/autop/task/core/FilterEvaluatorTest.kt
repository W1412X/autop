package com.example.autop.task.core

import com.example.autop.task.FilterClause
import com.example.autop.task.FilterGroup
import com.example.autop.task.FilterOp
import com.example.autop.task.GroupMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [FilterEvaluator] 单测（docs/tech/07 §2）。 */
internal class FilterEvaluatorTest {
    private val fields =
        mapOf(
            "package" to "com.tencent.mm",
            "title" to "收到请回复",
            "text" to "验证码 123456",
        )

    private fun clause(
        field: String,
        op: FilterOp,
        value: String,
        ignoreCase: Boolean = true,
    ) = FilterClause(field, op, value, ignoreCase)

    private fun single(clause: FilterClause): FilterGroup = FilterGroup(GroupMode.ALL, listOf(clause))

    /** 单条求值简写（压行宽）。 */
    private fun eval(clause: FilterClause): Boolean = FilterEvaluator.matches(single(clause), fields)

    @Test
    fun equalsIgnoreCase() {
        assertThat(eval(clause("package", FilterOp.EQUALS, "COM.TENCENT.MM"))).isTrue()
        assertThat(eval(clause("package", FilterOp.EQUALS, "x", ignoreCase = false))).isFalse()
    }

    @Test
    fun notEquals() {
        assertThat(eval(clause("package", FilterOp.NOT_EQUALS, "com.tencent.mm"))).isFalse()
        assertThat(eval(clause("package", FilterOp.NOT_EQUALS, "other"))).isTrue()
    }

    @Test
    fun containsAndNotContains() {
        assertThat(FilterEvaluator.matches(single(clause("text", FilterOp.CONTAINS, "验证码")), fields)).isTrue()
        assertThat(FilterEvaluator.matches(single(clause("text", FilterOp.NOT_CONTAINS, "验证码")), fields)).isFalse()
    }

    @Test
    fun startsWith() {
        assertThat(FilterEvaluator.matches(single(clause("title", FilterOp.STARTS_WITH, "收到")), fields)).isTrue()
        assertThat(FilterEvaluator.matches(single(clause("title", FilterOp.STARTS_WITH, "回复")), fields)).isFalse()
    }

    @Test
    fun regex() {
        assertThat(FilterEvaluator.matches(single(clause("text", FilterOp.REGEX, "\\d{6}")), fields)).isTrue()
        assertThat(FilterEvaluator.matches(single(clause("text", FilterOp.REGEX, "^[a-z]+$")), fields)).isFalse()
    }

    @Test
    fun regexCompileFailureIsNoMatch() {
        val group = single(clause("text", FilterOp.REGEX, "[unclosed"))
        assertThat(FilterEvaluator.matches(group, fields)).isFalse()
        assertThat(FilterEvaluator.brokenPatterns()).contains("[unclosed")
    }

    @Test
    fun inOperator() {
        assertThat(eval(clause("package", FilterOp.IN, "com.a|com.tencent.mm"))).isTrue()
        assertThat(eval(clause("package", FilterOp.IN, "com.a|com.b"))).isFalse()
    }

    @Test
    fun missingFieldNeverMatches() {
        assertThat(FilterEvaluator.matches(single(clause("missing", FilterOp.EXISTS, "")), fields)).isFalse()
        assertThat(FilterEvaluator.matches(single(clause("missing", FilterOp.EQUALS, "x")), fields)).isFalse()
        assertThat(FilterEvaluator.matches(single(clause("missing", FilterOp.NOT_EQUALS, "x")), fields)).isFalse()
    }

    @Test
    fun existsOnPresentField() {
        assertThat(FilterEvaluator.matches(single(clause("title", FilterOp.EXISTS, "")), fields)).isTrue()
    }

    @Test
    fun nestedAllAndAny() {
        val inner =
            FilterGroup(
                GroupMode.ANY,
                listOf(clause("title", FilterOp.EQUALS, "x"), clause("text", FilterOp.CONTAINS, "验证码")),
            )
        val outerAll = FilterGroup(GroupMode.ALL, emptyList(), listOf(inner))
        assertThat(FilterEvaluator.matches(outerAll, fields)).isTrue()
        val outerAny = FilterGroup(GroupMode.ANY, listOf(clause("package", FilterOp.EQUALS, "no")), listOf(inner))
        assertThat(FilterEvaluator.matches(outerAny, fields)).isTrue()
        val outerAnyMiss = FilterGroup(GroupMode.ANY, listOf(clause("package", FilterOp.EQUALS, "no")))
        assertThat(FilterEvaluator.matches(outerAnyMiss, fields)).isFalse()
    }
}
