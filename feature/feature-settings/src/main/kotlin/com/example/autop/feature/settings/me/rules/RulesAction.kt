/**
 * 安全规则页用户操作（docs/standards/coding-standards §8：sealed interface）。
 */
package com.example.autop.feature.settings.me.rules

public sealed interface RulesAction {
    /** 打开新建表单。 */
    public data object AddRule : RulesAction

    /** 打开某条自定义规则的编辑表单。 */
    public data class EditRule(
        val id: String,
    ) : RulesAction

    /** 删除某条自定义规则（[id] 为空串表示删除解析失败的原始条目）。 */
    public data class DeleteRule(
        val index: Int,
    ) : RulesAction

    /** 表单字段变更。 */
    public data class SetField(
        val draft: RuleDraft,
    ) : RulesAction

    /** 保存表单（新建或覆盖 [RuleDraft.originalId]）。 */
    public data object SaveRule : RulesAction

    /** 关闭表单。 */
    public data object CancelEdit : RulesAction

    /** 关闭校验错误提示。 */
    public data object DismissError : RulesAction
}
