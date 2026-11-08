/**
 * 进度悬浮窗 Run 结束元信息行（docs/tech/08 §5.3，N183）：summary（若有）+
 * 「任务状态 · n 步 · 用时 · n token」一行小字，与对话页 RunMetaRow 同款
 * （进度窗无复制/更多图标），不再是结束卡片。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.example.autop.agent.RunOutcome
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.overlay.R

/** 状态与统计文本之间的分隔符。 */
private const val META_SEPARATOR: String = " · "

/**
 * 结束元信息行（N183）：附着在回复末尾（[ProgressRow.Assistant.meta]）或独立成行；
 * 任务状态带语义色，失败 summary error 色。
 */
@Composable
internal fun FinishedRow(
    row: ProgressRow.Finished,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
    ) {
        row.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = metaTextColor(row.status),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(outcomeTextOf(row.status)),
                style = MaterialTheme.typography.labelSmall,
                color = outcomeMetaColor(row.status),
            )
            Text(
                text = META_SEPARATOR,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                    stringResource(
                        R.string.progress_result_stats,
                        row.stepCount,
                        durationText(row.durationMs),
                        row.outputTokens,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 任务状态小字颜色：成功 primary、失败 error、其余灰（同对话页 RunMetaRow）。 */
@Composable
private fun outcomeMetaColor(outcome: RunOutcome): Color =
    when (outcome) {
        RunOutcome.SUCCEEDED -> MaterialTheme.colorScheme.primary
        RunOutcome.FAILED -> MaterialTheme.colorScheme.error
        RunOutcome.CANCELLED,
        RunOutcome.BUDGET_EXHAUSTED,
        RunOutcome.BREAKER_STOPPED,
        RunOutcome.INTERRUPTED,
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

/** 用时文本（毫秒 → "n 秒" / "n 分 n 秒" / "n 小时 n 分"，同对话页）。 */
@Composable
private fun durationText(millis: Long): String {
    val totalSeconds = millis / MS_PER_SECOND
    return when {
        totalSeconds < SECONDS_PER_MINUTE -> stringResource(R.string.progress_duration_sec, totalSeconds)
        totalSeconds < SECONDS_PER_HOUR ->
            stringResource(
                R.string.progress_duration_min_sec,
                totalSeconds / SECONDS_PER_MINUTE,
                totalSeconds % SECONDS_PER_MINUTE,
            )
        else ->
            stringResource(
                R.string.progress_duration_hour_min,
                totalSeconds / SECONDS_PER_HOUR,
                totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
            )
    }
}

private const val MS_PER_SECOND: Long = 1000L
private const val SECONDS_PER_MINUTE: Long = 60L
private const val SECONDS_PER_HOUR: Long = 3600L
