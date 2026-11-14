/**
 * 时间线卡片渲染（docs/tech/08 §4.2）：暂停 / 拦截 / 中断 [StatusBanner]、
 * 等待回答卡（AskCard）；Run 元信息行见 RunMetaRow（N183）。
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.thread.ui.R
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone

/**
 * 暂停 / 拦截 / 中断横幅（操作按钮按种类给出，docs/tech/08 §4.2 StatusBanner）：
 * 按钮组右对齐，"继续"实心主按钮，"结束"error 文字按钮（N172 按钮区重排）。
 */
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
    val actions: (@Composable RowScope.() -> Unit)? =
        when (banner.kind) {
            BannerKind.PAUSED ->
                {
                    {
                        Spacer(modifier = Modifier.weight(1f))
                        AutopButton(
                            text = stringResource(R.string.thread_action_continue),
                            onClick = { onAction(TimelineAction.Resume) },
                        )
                    }
                }
            BannerKind.BLOCKED ->
                {
                    {
                        Spacer(modifier = Modifier.weight(1f))
                        AutopTextButton(
                            text = stringResource(R.string.thread_action_end),
                            onClick = { onAction(TimelineAction.Stop) },
                            destructive = true,
                        )
                        AutopButton(
                            text = stringResource(R.string.thread_action_continue),
                            onClick = { onAction(TimelineAction.Resume) },
                        )
                    }
                }
            BannerKind.INTERRUPTED -> null
        }
    StatusBanner(
        tone = bannerTone(banner.kind),
        message = banner.detail ?: stringResource(bannerText(banner.kind)),
        modifier = modifier.fillMaxWidth(),
        actions = actions,
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
