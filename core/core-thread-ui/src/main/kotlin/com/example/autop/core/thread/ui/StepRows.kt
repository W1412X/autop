/**
 * 步骤行渲染（docs/tech/08 §4.2 StepRow / StepGroup）：折叠行显示工具描述与耗时，
 * 点击展开参数 / 结果 / 观测详情（截图缩略图暂降级为文本，见 questions.md）。
 */
package com.example.autop.core.thread.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.example.autop.core.thread.ui.R
import com.example.autop.core.ui.component.AutopChip
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalMotionEnabled
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled
import com.example.autop.core.ui.theme.tone

/**
 * 步骤行（工具调用）：状态图标 + 描述 + 耗时；点击展开详情。
 *
 * @param step 步骤内容。
 * @param onLongClick 长按菜单（null 表示不开放，docs/tech/08 §4.2 长按 StepRow）。
 * @param onLinkClick 结果快捷入口点击（null 表示不渲染快捷入口）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StepRowView(
    step: TimelineItem.StepRow,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLinkClick: ((StepLink) -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = onLongClick,
                ),
    ) {
        StepSummary(step = step)
        if (step.state == StepState.FAILED) {
            step.resultText?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(
                            start = LocalSpacing.current.sm + IconSize.Sm.scaled() + LocalSpacing.current.xs * 2,
                            bottom = LocalSpacing.current.xs,
                        ),
                )
            }
        }
        val progress = step.progressText
        if (progress != null && step.state == StepState.RUNNING) {
            Text(
                text = progress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LocalSpacing.current.sm),
            )
        }
        if (onLinkClick != null) {
            StepLinks(links = step.links, onLinkClick = onLinkClick)
        }
        if (LocalMotionEnabled.current) {
            AnimatedVisibility(visible = expanded) {
                StepDetail(step = step)
            }
        } else if (expanded) {
            StepDetail(step = step)
        }
    }
}

/** 折叠态摘要行：工具类别图标 + 描述 + 耗时 + 状态小标（状态不只靠颜色，docs/tech/08 §6，N178）。 */
@Composable
private fun StepSummary(step: TimelineItem.StepRow) {
    val tone = LocalSemanticColors.current.tone(stepTone(step.state))
    Row(
        modifier =
            Modifier
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
                imageVector = toolIconOf(step.toolId),
                contentDescription = null,
                modifier = Modifier.size(IconSize.Sm.scaled()),
                tint = tone.foreground,
            )
        }
        if (step.stepNo > 0) {
            Text(
                text = stringResource(R.string.thread_step_no, step.stepNo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LocalSpacing.current.sm),
            )
        }
        Text(
            text = stepDesc(step),
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = LocalSpacing.current.sm),
        )
        timingText(step)?.let { timing ->
            Text(
                text = timing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = stateIconOf(step.state),
            contentDescription = null,
            modifier =
                Modifier
                    .padding(start = LocalSpacing.current.xs)
                    .size(IconSize.Xs.scaled()),
            tint = tone.foreground,
        )
    }
}

/** 结果上的快捷入口（任务/流程详情、编辑、AI 编辑）；未知 kind 不渲染。 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow 在当前 Compose 版本仍为实验 API
@Composable
private fun StepLinks(
    links: List<StepLink>,
    onLinkClick: (StepLink) -> Unit,
) {
    val known = links.filter { stepLinkLabelRes(it.kind) != 0 }
    if (known.isEmpty()) return
    val spacing = LocalSpacing.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier =
            Modifier.padding(
                start = spacing.sm + IconSize.Sm.scaled() + spacing.xs * 2,
                top = spacing.xs,
            ),
    ) {
        known.forEach { link ->
            AutopChip(
                text = link.label ?: stringResource(stepLinkLabelRes(link.kind)),
                selected = false,
                onClick = { onLinkClick(link) },
                icon = stepLinkIconOf(link.kind),
            )
        }
    }
}

private fun stepLinkLabelRes(kind: String): Int =
    when (kind) {
        StepLink.TASK_DETAIL,
        StepLink.FLOW_DETAIL,
        -> R.string.thread_link_detail
        StepLink.TASK_EDIT,
        StepLink.FLOW_EDIT,
        -> R.string.thread_link_edit
        StepLink.TASK_AI,
        StepLink.FLOW_AI,
        -> R.string.thread_link_ai
        else -> 0
    }

private fun stepLinkIconOf(kind: String): ImageVector =
    when (kind) {
        StepLink.TASK_EDIT,
        StepLink.FLOW_EDIT,
        -> AutopIcons.Edit
        StepLink.TASK_AI,
        StepLink.FLOW_AI,
        -> AutopIcons.Psychology
        else -> AutopIcons.Description
    }

/**
 * 连续读类步骤折叠行（docs/tech/08 §4.2 StepGroup："查看屏幕 ×n"）：
 * 点击展开组内步骤，链路完整可见（N172）。
 */
@Composable
internal fun StepGroupRow(
    group: TimelineItem.StepGroup,
    modifier: Modifier = Modifier,
    onLinkClick: ((StepLink) -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
    ) {
        Row(
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                        shape = MaterialTheme.shapes.small,
                    ).padding(all = LocalSpacing.current.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AutopIcons.Visibility,
                contentDescription = null,
                modifier = Modifier.size(IconSize.Sm.scaled()),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.thread_group, group.count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LocalSpacing.current.sm),
            )
        }
        if (LocalMotionEnabled.current) {
            AnimatedVisibility(visible = expanded) {
                GroupSteps(group = group, onLinkClick = onLinkClick)
            }
        } else if (expanded) {
            GroupSteps(group = group, onLinkClick = onLinkClick)
        }
    }
}

/** 展开的组内步骤（各自可再展开详情）。 */
@Composable
private fun GroupSteps(
    group: TimelineItem.StepGroup,
    onLinkClick: ((StepLink) -> Unit)?,
) {
    Column(modifier = Modifier.padding(start = LocalSpacing.current.md)) {
        group.steps.forEach { step ->
            StepRowView(step = step, onLongClick = null, onLinkClick = onLinkClick)
        }
    }
}

/** 展开的详情：参数（mono）/ 结果 / 观测摘要 / 截图占位。 */
@Composable
private fun StepDetail(step: TimelineItem.StepRow) {
    val mono =
        MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = LocalSpacing.current.md,
                    top = LocalSpacing.current.xs,
                    bottom = LocalSpacing.current.sm,
                ),
    ) {
        if (step.argsJson.isNotBlank()) {
            DetailLabel(res = R.string.thread_detail_args)
            Text(
                text = step.argsJson,
                style = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        step.resultText?.let { result ->
            DetailLabel(res = R.string.thread_detail_result)
            Text(text = result, style = MaterialTheme.typography.bodySmall)
        }
        step.observationLine?.let { line ->
            DetailLabel(res = R.string.thread_detail_observation)
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (step.imageRef != null) {
            Text(
                text = stringResource(R.string.thread_image_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLabel(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = LocalSpacing.current.xs),
    )
}

/** 步骤描述（StepDescriber 输出标签与参数，这里映射到 `step_*` 模板）。 */
@Composable
private fun stepDesc(step: TimelineItem.StepRow): String {
    val label = stepLabelOf(step.toolId)
    val arg = stepArgOf(step.toolId, step.argsJson)
    return when {
        label == StepLabel.OTHER -> stringResource(R.string.step_other, step.toolId)
        arg == null -> stringResource(label.verbRes)
        else -> stringResource(fmtOf(label), stringResource(label.verbRes), arg)
    }
}

/** 参数拼接模板（引号 / 直接 / 括号三类，docs/tech/08 §4.2 StepDescriber 文案）。 */
private fun fmtOf(label: StepLabel): Int =
    when (label) {
        StepLabel.TAP,
        StepLabel.INPUT_TEXT,
        StepLabel.CLIPBOARD_SET,
        -> R.string.step_desc_quoted
        StepLabel.SCROLL,
        StepLabel.GLOBAL_ACTION,
        -> R.string.step_desc_paren
        else -> R.string.step_desc_plain
    }

/** 耗时文本（毫秒 → 秒，保留一位小数）。 */
@Composable
private fun timingText(step: TimelineItem.StepRow): String? {
    val durationMs = step.durationMs ?: return null
    return stringResource(R.string.thread_step_secs, durationMs / MS_PER_SECOND_FLOAT)
}

private const val MS_PER_SECOND_FLOAT: Float = 1000f
