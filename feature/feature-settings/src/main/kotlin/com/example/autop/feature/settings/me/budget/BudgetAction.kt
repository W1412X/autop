/**
 * 运行预算页用户操作（docs/standards/coding-standards §8：sealed interface，
 * 用户操作统一通过 `onAction(action)` 交给 ViewModel）。
 */
package com.example.autop.feature.settings.me.budget

public sealed interface BudgetAction {
    /** 打开某维度的编辑弹层。 */
    public data class OpenEditor(
        val field: BudgetField,
    ) : BudgetAction

    /** 数值文本变化。 */
    public data class SetEditText(
        val text: String,
    ) : BudgetAction

    /** "不限预算"开关（开启后保存为 0）。 */
    public data class SetUnlimited(
        val unlimited: Boolean,
    ) : BudgetAction

    /** 保存当前编辑（写用户覆盖）。 */
    public data object Save : BudgetAction

    /** 恢复默认（清除用户覆盖；默认 = 不限）。 */
    public data object RestoreDefault : BudgetAction

    /** 关闭编辑弹层。 */
    public data object CloseEditor : BudgetAction
}
