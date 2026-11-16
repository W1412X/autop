/**
 * 时间线卡片（docs/tech/08 §4.2，ADR-0039）：运行横幅 [RunBannerCard]（暂停 / 拦截 / 中断）、
 * 等待回答卡 [AskQuestionCard]。对话页消息流、对话页提问区与进度悬浮窗共用，宿主只决定放置位置与动作响应。
 * Run 元信息行见 RunMetaRow（N183）。
 */
package com.example.autop.core.thread.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InlineQuestionCard
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone

/** 时间线横幅条目：暂停已恢复时为分割线，其余为 [RunBannerCard]。 */
@Composable
internal fun BannerRow(
    banner: TimelineItem.Banner,
    onAction: (TimelineAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (banner.kind == BannerKind.PAUSED && banner.resolved) {
        PauseDivider(text = banner.detail ?: stringResource(R.string.thread_pause_divider), modifier = modifier)
        return
    }
    RunBannerCard(kind = banner.kind, detail = banner.detail, onAction = onAction, modifier = modifier)
}

/**
 * 运行横幅（暂停 / 拦截 / 中断）：按钮按种类给出（暂停与拦截有"继续"，拦截还有"结束"，中断没有按钮）。
 * [pending] 为宿主正在执行的动作：对应按钮显示过渡文案并禁用，防止连点。
 */
@Composable
public fun RunBannerCard(
    kind: BannerKind,
    detail: String?,
    onAction: (TimelineAction) -> Unit,
    modifier: Modifier = Modifier,
    pending: TimelineAction? = null,
) {
    val actions: (@Composable RowScope.() -> Unit)? =
        when (kind) {
            BannerKind.PAUSED ->
                {
                    {
                        Spacer(modifier = Modifier.weight(1f))
                        ContinueButton(onAction = onAction, pending = pending)
                    }
                }
            BannerKind.BLOCKED ->
                {
                    {
                        Spacer(modifier = Modifier.weight(1f))
                        val stopping = pending == TimelineAction.Stop
                        val endRes = if (stopping) R.string.thread_action_stopping else R.string.thread_action_end
                        val endText = stringResource(endRes)
                        AutopTextButton(
                            text = endText,
                            onClick = { onAction(TimelineAction.Stop) },
                            destructive = true,
                            enabled = !stopping,
                        )
                        ContinueButton(onAction = onAction, pending = pending)
                    }
                }
            BannerKind.INTERRUPTED -> null
        }
    StatusBanner(
        tone = bannerTone(kind),
        message = detail ?: stringResource(bannerText(kind)),
        modifier = modifier.fillMaxWidth(),
        actions = actions,
    )
}

@Composable
private fun ContinueButton(
    onAction: (TimelineAction) -> Unit,
    pending: TimelineAction?,
) {
    val resuming = pending == TimelineAction.Resume
    AutopButton(
        text = stringResource(if (resuming) R.string.thread_action_resuming else R.string.thread_action_continue),
        onClick = { onAction(TimelineAction.Resume) },
        enabled = !resuming,
    )
}

/**
 * 等待回答卡（docs/tech/08 §4.2 AskCard）：问题 + 选项每行一个 + "自己输入"独立输入行。
 * 对话页与进度窗共用；选中或输入后由 [onAnswer] 回传。
 */
@Composable
public fun AskQuestionCard(
    question: String,
    options: List<String>,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InlineQuestionCard(
        question = question,
        options = options,
        onAnswer = onAnswer,
        customLabel = stringResource(R.string.thread_ask_self),
        sendDescription = stringResource(R.string.thread_send),
        modifier = modifier,
    )
}

private fun bannerTone(kind: BannerKind): StatusTone =
    when (kind) {
        BannerKind.PAUSED -> StatusTone.PAUSED
        BannerKind.BLOCKED -> StatusTone.BLOCKED
        BannerKind.INTERRUPTED -> StatusTone.NEUTRAL
    }

private fun bannerText(kind: BannerKind): Int =
    when (kind) {
        BannerKind.PAUSED -> R.string.thread_banner_paused
        BannerKind.BLOCKED -> R.string.thread_banner_blocked
        BannerKind.INTERRUPTED -> R.string.thread_banner_interrupted
    }

/** 暂停已恢复后的历史分割线：一条细线中间夹着说明文字，不带底色和按钮。 */
@Composable
internal fun PauseDivider(
    text: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.sm),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
