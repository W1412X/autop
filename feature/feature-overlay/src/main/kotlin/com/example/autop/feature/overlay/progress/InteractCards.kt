/**
 * 执行中交互区（进度窗与运行详情面板共用，ADR-0039）：提问卡与暂停/拦截横幅使用 core-thread-ui 的共享组件，
 * 这里只决定放在哪里、动作如何回到运行。补充指令输入行是悬浮窗自己的控件。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.agent.PauseReason
import com.example.autop.core.thread.ui.AskQuestionCard
import com.example.autop.core.thread.ui.BannerKind
import com.example.autop.core.thread.ui.RunBannerCard
import com.example.autop.core.thread.ui.TimelineAction
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.panel.ControlIntent
import com.example.autop.feature.overlay.panel.ControlIntentKind
import com.example.autop.feature.overlay.panel.controlIntentLabelRes
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** 提问卡 / 暂停或拦截横幅：只在需要时显示（N182 跨页面 ask_user 展示修复）。 */
@Composable
internal fun AlertCards(
    active: ActiveRunInfo?,
    callbacks: ProgressCallbacks,
    intent: ControlIntent? = null,
    modifier: Modifier = Modifier,
) {
    val run = active?.run ?: return
    val spacing = LocalSpacing.current
    val pending = pendingActionOf(intent)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        run.waitingQuestion?.let { question ->
            AskQuestionCard(
                question = question,
                options = run.waitingOptions,
                onAnswer = callbacks.onAnswer,
            )
        }
        when {
            run.pauseReason == PauseReason.POLICY_BLOCKED ->
                RunBannerCard(
                    kind = BannerKind.BLOCKED,
                    detail = run.summary,
                    onAction = callbacks.actionHandler(blocked = true),
                    pending = pending,
                )
            run.status == RunStatus.PAUSED ->
                RunBannerCard(
                    kind = BannerKind.PAUSED,
                    detail = null,
                    onAction = callbacks.actionHandler(),
                    pending = pending,
                )
        }
    }
}

/** 悬浮窗控制意图 → 共享横幅的过渡动作（点击即显示「正在…」并禁用，防连点）。 */
private fun pendingActionOf(intent: ControlIntent?): TimelineAction? =
    when (intent?.kind) {
        ControlIntentKind.RESUME -> TimelineAction.Resume
        ControlIntentKind.STOP -> TimelineAction.Stop
        ControlIntentKind.PAUSE, null -> null
    }

/** 过渡态按钮文案（控制行用）：命中意图换「正在…」，暂停/继续类由调用方同步禁用防连点。 */
@Composable
internal fun pendingLabelOf(
    intent: ControlIntent?,
    kind: ControlIntentKind,
    defaultRes: Int,
): String {
    val textRes = if (intent?.kind == kind) controlIntentLabelRes(kind) else defaultRes
    return stringResource(textRes)
}

/** steer 补充指令输入行（同运行面板 SteerRow）。 */
@Composable
internal fun SteerRow(
    callbacks: ProgressCallbacks,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        AutopInputBar(
            value = text,
            onValueChange = { text = it },
            spec = InputBarSpec(placeholder = stringResource(R.string.panel_steer_placeholder)),
            modifier = Modifier.weight(1f),
        )
        AutopIconButton(
            icon = Icons.Outlined.Send,
            contentDescription = stringResource(R.string.panel_steer_send),
            onClick = {
                if (text.isNotBlank()) {
                    callbacks.onSteer(text.trim())
                    text = ""
                }
            },
        )
    }
}
