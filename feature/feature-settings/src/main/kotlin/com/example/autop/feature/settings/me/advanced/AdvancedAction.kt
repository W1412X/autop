/**
 * 高级配置页用户操作（docs/standards/coding-standards §8）。
 */
package com.example.autop.feature.settings.me.advanced

public sealed interface AdvancedAction {
    /** 打开某配置段的 JSON 编辑器。 */
    public data class Open(
        val path: String,
    ) : AdvancedAction

    /** 编辑框文本变化。 */
    public data class SetEditText(
        val text: String,
    ) : AdvancedAction

    /** 保存编辑（写用户覆盖；校验失败显示错误且不生效）。 */
    public data object Save : AdvancedAction

    /** 清除该段的用户覆盖（恢复默认）。 */
    public data object ClearOverride : AdvancedAction

    /** 关闭编辑器。 */
    public data object CloseEditor : AdvancedAction
}
