/**
 * 进度悬浮窗（docs/tech/08 §5.3，N182）：聊天消息流（与对话页消息视觉一致）+
 * ask_user 提问卡 / 暂停拦截横幅 + 运行控制与补充指令。任何页面可见（自动弹出见 ProgressController）。
 * 执行中交互组件（提问卡/拦截横幅/补充指令行）见 InteractCards.kt，与运行详情面板共用。
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.tone
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** 状态色条高度（N185）。 */
private val STATUS_BAR_HEIGHT = 3.dp

/** 头部状态过渡时长（色条/氛围色/提示行，N185）。 */
private const val HEADER_TRANSITION_MS: Int = 250

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
            currentStep = flowHintOf(active) ?: active?.currentStep,
            onClose = callbacks.onClose,
        )
        ProgressMessageList(rows = rows, modifier = Modifier.weight(1f))
        AlertCards(active = active, callbacks = callbacks)
        ControlRow(active = active, expanded = header.runControls, callbacks = callbacks)
        if (header.runControls) {
            SteerRow(callbacks = callbacks)
        }
    }
}

/**
 * 头部（N185）：状态色条 + 氛围色头部块（标题/状态徽标/关闭）+ 提示行；
 * 色条与氛围色随状态语义过渡，提示行在等待回答/暂停/拦截与当前步骤间切换。
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
            // 流程执行中：只停流程、对话继续（P1-3 取消粒度）。
            if (active?.flow != null) {
                AutopTonalButton(
                    text = stringResource(R.string.panel_action_stop_flow),
                    onClick = callbacks.onStopFlow,
                )
            }
        }
        AutopTonalButton(
            text = stringResource(R.string.panel_action_open_chat),
            onClick = callbacks.onOpenThread,
        )
    }
}
