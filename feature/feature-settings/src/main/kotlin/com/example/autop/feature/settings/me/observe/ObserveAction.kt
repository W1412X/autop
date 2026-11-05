/**
 * 观测方式子页用户操作（docs/design/observation-modes-coordinate-actions §2.3、§7.1）。
 */
package com.example.autop.feature.settings.me.observe

public sealed interface ObserveAction {
    /** 启用/关闭某个观测模式。 */
    public data class SetEnabled(
        val modeId: String,
        val enabled: Boolean,
    ) : ObserveAction

    /** 在优先级列表中上移一位。 */
    public data class MoveUp(
        val modeId: String,
    ) : ObserveAction

    /** 在优先级列表中下移一位。 */
    public data class MoveDown(
        val modeId: String,
    ) : ObserveAction

    /** 跳到模型配置页（llm 模式不可用时的入口）。 */
    public data object OpenModelSettings : ObserveAction
}
