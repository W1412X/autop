/**
 * 输入面板动作回调聚合（docs/tech/08 §5.2，N182）：由 [PanelController] 构造，
 * 动作实现在 [PanelRunActions]。运行/问题/拦截的动作回调在进度悬浮窗（ProgressCallbacks）。
 */
package com.example.autop.feature.overlay.panel

/** 输入面板动作回调聚合。 */
internal class PanelCallbacks(
    val onSend: (text: String, attachScreen: Boolean) -> Unit,
    val onDismiss: () -> Unit,
)
