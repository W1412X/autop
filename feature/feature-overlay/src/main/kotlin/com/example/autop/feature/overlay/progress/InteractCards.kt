/**
 * 执行中共用交互组件（N182 提问卡 / 拦截横幅 / 补充指令行）：进度窗与运行详情面板
 * （顶部控制条点开的消息条）共用——执行中交互统一走控制条＋消息条体系，就地完成。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.agent.PauseReason
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopOptionRow
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** 「自己输入」独立输入行的最大行数（同 AskCard，N181）。 */
private const val ASK_INPUT_MAX_LINES: Int = 3

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

/** 提问卡 / 暂停拦截横幅（只在需要时显示，N182 跨页面 ask_user 展示修复）。 */
@Composable
internal fun AlertCards(
    active: ActiveRunInfo?,
    callbacks: ProgressCallbacks,
    modifier: Modifier = Modifier,
) {
    val run = active?.run ?: return
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        run.waitingQuestion?.let { question ->
            AskCard(
                question = question,
                options = run.waitingOptions,
                callbacks = callbacks,
            )
        }
        when {
            run.pauseReason == PauseReason.POLICY_BLOCKED ->
                StatusBanner(
                    tone = StatusTone.BLOCKED,
                    message = run.summary ?: stringResource(R.string.panel_blocked_reason_default),
                    actions = {
                        Spacer(modifier = Modifier.weight(1f))
                        AutopTextButton(
                            text = stringResource(R.string.panel_blocked_finish),
                            destructive = true,
                            onClick = callbacks.onFinishBlocked,
                        )
                        AutopButton(
                            text = stringResource(R.string.panel_blocked_continue),
                            onClick = callbacks.onContinueBlocked,
                        )
                    },
                )
            run.status == RunStatus.PAUSED ->
                StatusBanner(
                    tone = StatusTone.PAUSED,
                    message = stringResource(R.string.panel_status_paused),
                    actions = {
                        Spacer(modifier = Modifier.weight(1f))
                        AutopButton(
                            text = stringResource(R.string.panel_action_resume),
                            onClick = callbacks.onPauseResume,
                        )
                    },
                )
        }
    }
}

/** 等待回答卡（AskCard 同款，N181/N182）：问题 + 选项每行一个 + 「自己输入」独立输入行。 */
@Composable
internal fun AskCard(
    question: String,
    options: List<String>,
    callbacks: ProgressCallbacks,
    modifier: Modifier = Modifier,
) {
    var selfInput by remember { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ).padding(all = LocalSpacing.current.md),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        Text(text = question, style = MaterialTheme.typography.titleSmall)
        options.forEach { option ->
            AutopOptionRow(text = option, onClick = { callbacks.onAnswer(option) })
        }
        if (selfInput) {
            AskInputRow(callbacks = callbacks)
        } else {
            AutopTextButton(
                text = stringResource(R.string.panel_ask_self),
                onClick = { selfInput = true },
            )
        }
    }
}

/** 「自己输入」展开后的独立输入行（N181：不占用对话底部输入框）。 */
@Composable
private fun AskInputRow(
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
            spec = InputBarSpec(maxLines = ASK_INPUT_MAX_LINES),
            modifier = Modifier.weight(1f),
        )
        AutopTextButton(
            text = stringResource(R.string.panel_send),
            onClick = {
                if (text.isNotBlank()) {
                    callbacks.onAnswer(text.trim())
                    text = ""
                }
            },
        )
    }
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
