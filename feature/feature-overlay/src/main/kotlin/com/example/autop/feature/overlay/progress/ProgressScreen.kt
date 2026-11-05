/**
 * 进度悬浮窗（docs/tech/08 §5.3，N182）：聊天消息流（与对话页消息视觉一致）+
 * ask_user 提问卡 / 暂停拦截横幅 + 运行控制与补充指令。任何页面可见（自动弹出见 ProgressController）。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.agent.PauseReason
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopOptionRow
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.tone
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** 「自己输入」独立输入行的最大行数（同 AskCard，N181）。 */
private const val ASK_INPUT_MAX_LINES: Int = 3

/** 状态色条高度（N185）。 */
private val STATUS_BAR_HEIGHT = 3.dp

/** 头部状态过渡时长（色条/氛围色/提示行，N185）。 */
private const val HEADER_TRANSITION_MS: Int = 250

/** 进度悬浮窗动作回调聚合（由 ProgressController 构造，动作实现在 PanelRunActions）。 */
@Suppress("LongParameterList") // 回调聚合容器：八个动作入口不可再减（questions.md N114 口径）
internal class ProgressCallbacks(
    val onAnswer: (String) -> Unit,
    val onPauseResume: () -> Unit,
    val onStop: () -> Unit,
    val onOpenThread: () -> Unit,
    val onSteer: (String) -> Unit,
    val onContinueBlocked: () -> Unit,
    val onFinishBlocked: () -> Unit,
    val onClose: () -> Unit,
)

/** 进度悬浮窗内容：头部状态 + 消息流 + 提问/横幅卡 + 控制与补充指令。 */
@Composable
internal fun ProgressScreen(
    active: ActiveRunInfo?,
    rows: List<ProgressRow>,
    callbacks: ProgressCallbacks,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ).padding(all = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        val finished =
            rows.reversed().firstNotNullOfOrNull { row ->
                when (row) {
                    is ProgressRow.Finished -> row
                    is ProgressRow.Assistant -> row.meta
                    else -> null
                }
            }
        val header = progressHeaderSpec(active = active, finished = finished)
        HeaderBlock(
            header = header,
            currentStep = active?.currentStep,
            onClose = callbacks.onClose,
        )
        MessageList(rows = rows, modifier = Modifier.weight(1f))
        AlertCards(active = active, callbacks = callbacks)
        ControlRow(active = active, expanded = header.runControls, callbacks = callbacks)
        if (header.runControls) {
            SteerRow(callbacks = callbacks)
        }
    }
}

/**
 * 头部（N185）：状态色条 + 氛围色头部块（标题/状态徽标/关闭）+ 提示行；
 * 色条与氛围色随状态语义过渡，提示行在“等待回答/暂停/拦截”与当前步骤间切换。
 */
@Composable
private fun HeaderBlock(
    header: ProgressHeaderSpec,
    currentStep: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val foreground by animateColorAsState(
        targetValue = LocalSemanticColors.current.tone(header.tone).foreground,
        animationSpec = tween(HEADER_TRANSITION_MS),
        label = "progress_header_tint",
    )
    val container by animateColorAsState(
        targetValue = LocalSemanticColors.current.tone(header.tone).container,
        animationSpec = tween(HEADER_TRANSITION_MS),
        label = "progress_header_bg",
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // 状态色条：窗口顶部圆角横条，颜色即当前状态语义。
        Box(
            Modifier
                .fillMaxWidth()
                .height(STATUS_BAR_HEIGHT)
                .background(foreground, CircleShape),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = container.copy(alpha = AutopAlpha.BLOCK_FILL),
                        shape = MaterialTheme.shapes.medium,
                    ).padding(all = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.progress_title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = stringResource(header.chipText),
                    tone = header.tone,
                )
                AutopIconButton(
                    icon = AutopIcons.Close,
                    contentDescription = stringResource(R.string.progress_close),
                    onClick = onClose,
                )
            }
            HeaderHint(hint = header.hint, currentStep = currentStep, tint = foreground)
        }
    }
}

/**
 * 提示行（N185）：等待回答 / 已暂停 / 被拦截时显示提示文案（状态前景色，醒目），
 * 其余时候显示当前步骤单行；切换时 crossfade 过渡。
 */
@Composable
private fun HeaderHint(
    hint: Int?,
    currentStep: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Crossfade(targetState = hint to currentStep, modifier = modifier, label = "progress_hint") { (hintText, step) ->
        when {
            hintText != null ->
                Text(
                    text = stringResource(hintText),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                )
            step != null ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
        }
    }
}

/** 消息流（chat 页消息同款，N182）：新行自动滚到底。 */
@Composable
private fun MessageList(
    rows: List<ProgressRow>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        items(rows.size) { index ->
            ProgressRowView(row = rows[index])
        }
    }
}

/** 提问卡 / 暂停拦截横幅（只在需要时显示，N182 跨页面 ask_user 展示修复）。 */
@Composable
private fun AlertCards(
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
private fun AskCard(
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

/** 控制行（N185）：运行中暂停/停止/打开对话；结束后收起运行控制只留「打开对话」。 */
@Composable
private fun ControlRow(
    active: ActiveRunInfo?,
    expanded: Boolean,
    callbacks: ProgressCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        if (expanded) {
            AutopTonalButton(
                text =
                    stringResource(
                        if (active?.run?.status == RunStatus.PAUSED) {
                            R.string.panel_action_resume
                        } else {
                            R.string.panel_action_pause
                        },
                    ),
                onClick = callbacks.onPauseResume,
            )
            AutopTonalButton(
                text = stringResource(R.string.panel_action_stop),
                onClick = callbacks.onStop,
            )
        }
        AutopTonalButton(
            text = stringResource(R.string.panel_action_open_chat),
            onClick = callbacks.onOpenThread,
        )
    }
}

/** steer 补充指令输入行（同运行面板 SteerRow）。 */
@Composable
private fun SteerRow(
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
