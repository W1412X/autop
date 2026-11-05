/**
 * 提示词页用户操作（docs/standards/coding-standards §8）。
 */
package com.example.autop.feature.settings.me.prompts

public sealed interface PromptsAction {
    /** 打开某模板的正文编辑器。 */
    public data class Open(
        val id: String,
    ) : PromptsAction

    /** 编辑框文本变化。 */
    public data class SetEditText(
        val text: String,
    ) : PromptsAction

    /** 保存用户覆盖正文（未声明变量显示错误且不生效）。 */
    public data object Save : PromptsAction

    /** 清除该模板的用户覆盖（恢复内置正文）。 */
    public data object ClearOverride : PromptsAction

    /** 关闭编辑器。 */
    public data object CloseEditor : PromptsAction
}
