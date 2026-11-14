/**
 * Run 结束元信息行（docs/tech/08 §4.2，N183）：summary（若有）+「任务状态 · n 步 ·
 * 用时 · n token」一行小字 + 复制/更多图标，直接附着在回复末尾，不再是独立面板。
 */
package com.example.autop.core.thread.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.example.autop.agent.RunOutcome
import com.example.autop.core.thread.ui.R
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.llmErrorTextRes
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/** 状态与统计文本之间的分隔符。 */
private const val META_SEPARATOR: String = " · "

/**
 * Run 元信息行：summary（失败 error 色）+ 一行小字（任务状态带语义色 + 统计）+
 * 复制/更多图标（截图样式）。[copyText] 为复制内容（回复文本，独立条目回退 summary）；
 * [seq] 为空（流式未落盘）时不显示"更多"。
 */
@Composable
internal fun RunMetaRow(
    result: TimelineItem.RunMeta,
    copyText: String?,
    seq: Long?,
    onAction: (TimelineAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        (result.summary ?: failureText(result))?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = metaTextColor(result.outcome),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = outcomeText(result.outcome),
                style = MaterialTheme.typography.labelSmall,
                color = outcomeMetaColor(result.outcome),
            )
            Text(
                text = META_SEPARATOR,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                    stringResource(
                        R.string.thread_result_stats,
                        result.stepCount,
                        durationText(result.durationMs),
                        result.outputTokens,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (copyText != null) {
                MetaIcon(
                    icon = AutopIcons.Copy,
                    contentDescription = stringResource(R.string.thread_meta_copy),
                    onClick = {
                        clipboard.setText(AnnotatedString(copyText))
                        onAction(TimelineAction.CopyReply)
                    },
                )
            }
            if (seq != null) {
                MetaIcon(
                    icon = AutopIcons.MoreVert,
                    contentDescription = stringResource(R.string.thread_meta_more),
                    onClick = { onAction(TimelineAction.ShowEntryMenu(seq)) },
                )
            }
        }
    }
}

/** 行内小图标按钮（视觉 Xs、触控区由 IconButton 保证 ≥48dp）。 */
@Composable
private fun MetaIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(IconSize.Xs.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 任务状态小字颜色：成功 primary、失败 error、其余灰。 */
@Composable
private fun outcomeMetaColor(outcome: RunOutcome): Color =
    when (outcome) {
        RunOutcome.SUCCEEDED -> MaterialTheme.colorScheme.primary
        RunOutcome.FAILED -> MaterialTheme.colorScheme.error
        RunOutcome.CANCELLED,
        RunOutcome.BUDGET_EXHAUSTED,
        RunOutcome.BREAKER_STOPPED,
        RunOutcome.INTERRUPTED,
        RunOutcome.EXPIRED,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** summary 文本颜色（失败 error，其余灰）。 */
@Composable
private fun metaTextColor(outcome: RunOutcome): Color =
    if (outcome == RunOutcome.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun outcomeText(outcome: RunOutcome): String =
    stringResource(
        when (outcome) {
            RunOutcome.SUCCEEDED -> R.string.chat_outcome_succeeded
            RunOutcome.FAILED -> R.string.chat_outcome_failed
            RunOutcome.CANCELLED -> R.string.chat_outcome_cancelled
            RunOutcome.BUDGET_EXHAUSTED -> R.string.chat_outcome_budget
            RunOutcome.BREAKER_STOPPED -> R.string.chat_outcome_breaker
            RunOutcome.INTERRUPTED -> R.string.chat_outcome_interrupted
            RunOutcome.EXPIRED -> R.string.chat_outcome_expired
        },
    )

/** 用时文本（毫秒 → "n 秒" / "n 分 n 秒" / "n 小时 n 分"）。 */
@Composable
private fun durationText(millis: Long): String {
    val totalSeconds = millis / MS_PER_SECOND
    return when {
        totalSeconds < SECONDS_PER_MINUTE -> stringResource(R.string.thread_duration_sec, totalSeconds)
        totalSeconds < SECONDS_PER_HOUR ->
            stringResource(
                R.string.thread_duration_min_sec,
                totalSeconds / SECONDS_PER_MINUTE,
                totalSeconds % SECONDS_PER_MINUTE,
            )
        else ->
            stringResource(
                R.string.thread_duration_hour_min,
                totalSeconds / SECONDS_PER_HOUR,
                totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
            )
    }
}

/** 失败且没有摘要时，用错误码对应的提示文案说明原因（不展示原始错误码）。 */
@Composable
private fun failureText(result: TimelineItem.RunMeta): String? =
    if (result.outcome == RunOutcome.FAILED) {
        result.errorCode?.let { stringResource(llmErrorTextRes(it)) }
    } else {
        null
    }

private const val MS_PER_SECOND: Long = 1000L
private const val SECONDS_PER_MINUTE: Long = 60L
private const val SECONDS_PER_HOUR: Long = 3600L
