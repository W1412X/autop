/**
 * 契约语言的统一（docs/design/flows-contracts-evidence §7.1）：`Expect.kind`
 * 可转换成等价 [Condition]，引擎内部统一按条件处理；`cond` 优先于 `kind`。
 */
package com.example.autop.flow.core

import com.example.autop.flow.Condition
import com.example.autop.flow.ElementOp
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.TextOp

/**
 * 结果契约 → 条件。`cond` 非空时直接返回；否则按 `kind` 转换。
 * `none` / `screenChanged` 不是可核对的状态，返回 null（不参与契约继承与核验）。
 */
public fun Expect.toCondition(): Condition? {
    cond?.let { return it }
    return when (kind) {
        ExpectKind.NONE -> null
        ExpectKind.SCREEN_CHANGED -> null
        ExpectKind.TEXT -> text?.let { Condition.ScreenText(op = TextOp.CONTAINS, value = it) }
        ExpectKind.TEXT_GONE -> text?.let { Condition.ScreenText(op = TextOp.NOT_CONTAINS, value = it) }
        ExpectKind.ELEMENT -> target?.let { Condition.Element(op = ElementOp.EXISTS, target = it) }
        ExpectKind.ELEMENT_GONE -> target?.let { Condition.Element(op = ElementOp.ABSENT, target = it) }
        ExpectKind.APP -> app?.let { Condition.App(value = it) }
    }
}
