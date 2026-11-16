package com.example.autop.feature.overlay.progress

import com.example.autop.core.thread.ui.TimelineAction

/** 运行交互动作回调聚合（由各面板装配层构造，动作实现在 PanelRunActions）。 */
@Suppress("LongParameterList") // 回调聚合容器：一个动作入口不可再减（questions.md N114 口径）。
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

/**
 * 时间线动作 → 悬浮窗回调（ADR-0039）。悬浮窗不开放长按菜单与复制提示；链接与分叉来源统一打开对话页。
 * [blocked] 为 true 时，继续/结束分派到拦截横幅的语义（继续拦截、结束运行），否则为暂停语义。
 */
internal fun ProgressCallbacks.actionHandler(blocked: Boolean = false): (TimelineAction) -> Unit =
    { action ->
        when (action) {
            is TimelineAction.ShowEntryMenu -> Unit
            TimelineAction.CopyReply -> Unit
            TimelineAction.Resume -> if (blocked) onContinueBlocked() else onPauseResume()
            TimelineAction.Stop -> if (blocked) onFinishBlocked() else onStop()
            is TimelineAction.OpenLink -> onOpenThread()
            is TimelineAction.OpenSourceThread -> onOpenThread()
        }
    }
