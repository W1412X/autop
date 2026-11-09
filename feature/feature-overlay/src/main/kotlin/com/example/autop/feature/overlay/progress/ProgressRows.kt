/**
 * 进度悬浮窗消息行渲染（docs/tech/08 §5.3，N182）：用户气泡 / 助手 Markdown / 思考折叠 /
 * 工具步骤行 / 计划卡 / 结束元信息行（ProgressFinishedRow）——视觉与对话页消息一致
 * （§4.2 同款底色、圆角、字阶）。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.timeline.StepState
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.MarkdownText
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.core.ui.theme.tone
import com.example.autop.feature.overlay.R

/** 消息行分派（N182）。 */
@Composable
internal fun ProgressRowView(
    row: ProgressRow,
    modifier: Modifier = Modifier,
) {
    when (row) {
        is ProgressRow.User -> UserBubbleRow(row = row, modifier = modifier)
        is ProgressRow.Assistant -> AssistantRow(row = row, modifier = modifier)
        is ProgressRow.Thinking -> ThinkingRow(row = row, modifier = modifier)
        is ProgressRow.Tool -> ToolRow(row = row, modifier = modifier)
        is ProgressRow.Plan -> PlanCardRow(row = row, modifier = modifier)
        is ProgressRow.Finished -> FinishedRow(row = row, modifier = modifier)
    }
}

/**
 * 助手文本（Markdown）+ 末尾附着的结束元信息行（N183）；
 * 流式同路渲染并收口半截标记 + 闪烁光标（N186 对齐对话页 AssistantTextRow）。
 */
@Composable
private fun AssistantRow(
    row: ProgressRow.Assistant,
    modifier: Modifier = Modifier,
) {
    val textModifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = LocalSpacing.current.sm)
    Column(modifier = modifier.fillMaxWidth()) {
        MarkdownText(
            text = row.text,
            modifier = textModifier,
            streaming = row.streaming,
            cursorAlpha = if (row.streaming) cursorAlpha() else CURSOR_ALPHA_MAX,
        )
        row.meta?.let { meta -> FinishedRow(row = meta, modifier = Modifier.fillMaxWidth()) }
    }
}

/** 用户气泡：右对齐、primaryContainer 底、medium 圆角右下角 extraSmall（同对话页 UserBubble）。 */
@Composable
private fun UserBubbleRow(
    row: ProgressRow.User,
    modifier: Modifier = Modifier,
) {
    val bubbleMaxWidth = LocalConfiguration.current.screenWidthDp.dp * USER_BUBBLE_WIDTH_FRACTION
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = userBubbleShape(),
                    ).padding(all = LocalSpacing.current.md),
        ) {
            if (row.isSteer) {
                Text(
                    text = stringResource(R.string.progress_steer_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 气泡形状：medium 圆角、右下角改 extraSmall（同对话页，token 组合）。 */
@Composable
private fun userBubbleShape(): CornerBasedShape =
    MaterialTheme.shapes.medium.copy(bottomEnd = MaterialTheme.shapes.extraSmall.topStart)

/**
 * 思考块：流式自动展开实时显示（带光标），落定折叠「思考 · n 字」；
 * 手动展开/折叠优先于自动状态（同对话页 ThinkingRow，N186 对齐）。
 */
@Composable
private fun ThinkingRow(
    row: ProgressRow.Thinking,
    modifier: Modifier = Modifier,
) {
    var userToggled by remember { mutableStateOf(false) }
    var userOpen by remember { mutableStateOf(false) }
    val expanded = if (userToggled) userOpen else row.streaming
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable {
                    userToggled = true
                    userOpen = !expanded
                }.padding(vertical = LocalSpacing.current.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AutopIcons.Psychology,
                contentDescription = null,
                modifier = Modifier.size(IconSize.Sm.scaled()),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.progress_thinking, row.text.length),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LocalSpacing.current.xs),
            )
        }
        if (expanded) {
            val body =
                if (row.streaming) {
                    buildAnnotatedString {
                        append(row.text)
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha()))) {
                            append(STREAM_CURSOR_GLYPH)
                        }
                    }
                } else {
                    AnnotatedString(row.text)
                }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LocalSpacing.current.xs),
            )
        }
    }
}

/** 工具步骤行：类别图标（tone 底）+ 工具 id + 执行中进度/耗时 + 状态标（同对话页 StepRow）。 */
@Composable
private fun ToolRow(
    row: ProgressRow.Tool,
    modifier: Modifier = Modifier,
) {
    val stepTone =
        when (row.state) {
            StepState.RUNNING -> StatusTone.RUNNING
            StepState.DONE -> StatusTone.SUCCEEDED
            StepState.FAILED -> StatusTone.BLOCKED
        }
    val tone = LocalSemanticColors.current.tone(stepTone)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = LocalSpacing.current.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .background(color = tone.container, shape = MaterialTheme.shapes.extraSmall)
                    .padding(LocalSpacing.current.xs),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = toolIconOf(row.toolId),
                contentDescription = null,
                modifier = Modifier.size(IconSize.Sm.scaled()),
                tint = tone.foreground,
            )
        }
        Text(
            text = toolNameOf(row.toolId),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.sm),
        )
        ToolTrailing(row = row, tint = tone.foreground)
    }
    if (row.state == StepState.FAILED && row.errorText != null) {
        val indent = LocalSpacing.current.sm + IconSize.Sm.scaled() + LocalSpacing.current.xs * ERROR_TEXT_INDENT
        Text(
            text = row.errorText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = indent, bottom = LocalSpacing.current.xs),
        )
    }
}

/** 工具行尾部：执行中给进度提示，完成/失败给耗时与状态标。 */
@Composable
private fun ToolTrailing(
    row: ProgressRow.Tool,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (row.state == StepState.RUNNING) {
        RunningHint(progressText = row.progressText, tint = tint, modifier = modifier)
    } else {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            row.durationMs?.let { duration ->
                Text(
                    text = stringResource(R.string.progress_tool_duration, duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (row.state == StepState.DONE) AutopIcons.Check else AutopIcons.Error,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(IconSize.Xs.scaled())
                        .padding(start = LocalSpacing.current.xs),
                tint = tint,
            )
        }
    }
}

/**
 * 执行中提示：进度文本（无则「执行中」）+ 小转圈（[LocalMotionEnabled] 关闭时静态图标），
 * 颜色随工具行状态语义。
 */
@Composable
private fun RunningHint(
    progressText: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = progressText ?: stringResource(R.string.progress_tool_running),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            modifier = Modifier.widthIn(max = RUNNING_HINT_MAX_WIDTH),
        )
        Box(
            modifier =
                Modifier
                    .padding(start = LocalSpacing.current.xs)
                    .size(IconSize.Xs.scaled()),
            contentAlignment = Alignment.Center,
        ) {
            if (LocalMotionEnabled.current) {
                CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.Xs.scaled()),
                    strokeWidth = RUNNING_SPINNER_STROKE,
                    color = tint,
                )
            } else {
                Icon(
                    imageVector = AutopIcons.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.Xs.scaled()),
                    tint = tint,
                )
            }
        }
    }
}

/** 计划卡（PlanUpdated 覆盖式，同对话页 PlanCard）。 */
@Composable
private fun PlanCardRow(
    row: ProgressRow.Plan,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ).padding(all = LocalSpacing.current.md),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        Text(
            text = stringResource(R.string.progress_plan_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        row.explanation?.let { explanation ->
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.items.forEach { plan -> PlanLine(plan = plan) }
    }
}

@Composable
private fun PlanLine(plan: PlanItem) {
    val icon =
        when (plan.status) {
            PlanStatus.PENDING -> AutopIcons.RadioButtonUnchecked
            PlanStatus.IN_PROGRESS -> AutopIcons.Play
            PlanStatus.DONE -> AutopIcons.CheckCircle
            PlanStatus.DROPPED -> AutopIcons.Close
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Xs.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = plan.step,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.xs),
        )
    }
}

/** RunOutcome → 徽标语义。 */
internal fun outcomeToneOf(outcome: RunOutcome): StatusTone =
    when (outcome) {
        RunOutcome.SUCCEEDED -> StatusTone.SUCCEEDED
        RunOutcome.FAILED -> StatusTone.BLOCKED
        RunOutcome.CANCELLED -> StatusTone.NEUTRAL
        RunOutcome.BUDGET_EXHAUSTED -> StatusTone.PAUSED
        RunOutcome.BREAKER_STOPPED -> StatusTone.BLOCKED
        RunOutcome.INTERRUPTED -> StatusTone.PAUSED
    }

/** 流式光标闪烁透明度（同对话页，动效受 LocalMotionEnabled 总开关：关闭时静态光标）。 */
@Composable
private fun cursorAlpha(): Float {
    if (!LocalMotionEnabled.current) return CURSOR_ALPHA_MAX
    val transition = rememberInfiniteTransition(label = "progress_cursor")
    val alpha by transition.animateFloat(
        initialValue = CURSOR_ALPHA_MIN,
        targetValue = CURSOR_ALPHA_MAX,
        animationSpec = infiniteRepeatable(tween(CURSOR_BLINK_MS), RepeatMode.Reverse),
        label = "progress_cursor_alpha",
    )
    return alpha
}

private const val USER_BUBBLE_WIDTH_FRACTION: Float = 0.8f

/** 执行中进度文本最大宽度（超长截断，避免挤压工具名）。 */
private val RUNNING_HINT_MAX_WIDTH = 120.dp

/** 执行中转圈描边宽度。 */
private val RUNNING_SPINNER_STROKE = 2.dp

/** 失败错误文本左缩进（图标宽 + 间隙的倍数）。 */
private const val ERROR_TEXT_INDENT: Int = 2

private const val STREAM_CURSOR_GLYPH: String = "▍"

private const val CURSOR_BLINK_MS: Int = 530

private const val CURSOR_ALPHA_MIN: Float = 0.2f

private const val CURSOR_ALPHA_MAX: Float = 1f
