package com.example.autop.feature.overlay.progress

/** 运行中交互动作回调聚合（由各面板装配层构造，动作实现在 PanelRunActions）。 */
@Suppress("LongParameterList") // 回调聚合容器：九个动作入口不可再减（questions.md N114 口径）
internal class ProgressCallbacks(
    val onAnswer: (String) -> Unit,
    val onPauseResume: () -> Unit,
    val onStop: () -> Unit,
    val onStopFlow: () -> Unit,
    val onOpenThread: () -> Unit,
    val onSteer: (String) -> Unit,
    val onContinueBlocked: () -> Unit,
    val onFinishBlocked: () -> Unit,
    val onClose: () -> Unit,
)
