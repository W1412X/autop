/**
 * 条件求值明细的验收用例（docs/design/flows-precondition-selfrepair.md P3）。
 *
 * 明细要能说清"哪个子条件不成立"，并把"条件写错"与"条件为假"分开。
 */
package com.example.autop.flow.core

import com.example.autop.flow.Condition
import com.example.autop.flow.TextOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionDetailTest {
    private val h = FlowTestHarness()

    @Test
    fun `all collects the detail of every child`() {
        h.screenText = "发布页"
        val detail =
            h.detail(
                Condition.All(
                    of =
                        listOf(
                            Condition.ScreenText(op = TextOp.CONTAINS, value = "发布页"),
                            Condition.ScreenText(op = TextOp.CONTAINS, value = "不存在"),
                        ),
                ),
            )
        assertFalse(detail.ok)
        assertEquals(2, detail.children.size)
        assertTrue(detail.children[0].ok)
        assertFalse(detail.children[1].ok)
        assertTrue(detail.children[0].label.contains("页面文字"))
        assertNotNull(detail.children[0].actual)
    }

    @Test
    fun `any is satisfied when one child holds`() {
        h.screenText = "发布页"
        val detail =
            h.detail(
                Condition.Any(
                    of =
                        listOf(
                            Condition.ScreenText(op = TextOp.CONTAINS, value = "发布页"),
                            Condition.ScreenText(op = TextOp.CONTAINS, value = "不存在"),
                        ),
                ),
            )
        assertTrue(detail.ok)
        assertEquals(2, detail.children.size)
    }

    @Test
    fun `not inverts the inner result`() {
        h.screenText = "发布页"
        val detail =
            h.detail(
                Condition.Not(of = Condition.ScreenText(op = TextOp.CONTAINS, value = "发布页")),
            )
        assertFalse(detail.ok)
        assertEquals(1, detail.children.size)
        assertTrue(detail.children[0].ok)
    }

    @Test
    fun `invalid regex is reported as an error instead of a false result`() {
        h.screenText = "发布页"
        val detail = h.detail(Condition.ScreenText(op = TextOp.REGEX, value = "["))
        assertFalse(detail.ok)
        assertNotNull("非法正则应作为错误返回", detail.error)
        assertTrue(detail.error!!.contains("flow.expr_error"))
    }

    @Test
    fun `template failure is reported as an error`() {
        val detail = h.detail(Condition.ScreenText(op = TextOp.CONTAINS, value = "{{missingVar}}"))
        assertFalse(detail.ok)
        assertNotNull(detail.error)
    }

    @Test
    fun `app condition reports the foreground package`() {
        h.foregroundPackage = "com.demo"
        val detail = h.detail(Condition.App(value = "com.demo"))
        assertTrue(detail.ok)
        assertEquals("前台 com.demo", detail.actual)
        assertTrue(detail.label.contains("前台应用"))
    }

    @Test
    fun `normal conditions carry no error`() {
        h.screenText = "发布页"
        val detail = h.detail(Condition.ScreenText(op = TextOp.CONTAINS, value = "发布页"))
        assertTrue(detail.ok)
        assertNull(detail.error)
    }
}
