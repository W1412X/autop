/**
 * 时间线卡片渲染（docs/tech/08 §4.2）：暂停 / 拦截 / 中断 [StatusBanner]、
 * 等待回答卡（AskCard）；Run 元信息行见 RunMetaRow（N183）。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopOptionRow
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.chat.R

/**
 * 暂停 / 拦截 / 中断横幅（操作按钮按种类给出，docs/tech/08 §4.2 StatusBanner）：
 * 按钮组右对齐，"继续"实心主按钮，"结束"error 文字按钮（N172 按钮区重排）。
 */
@Composable
internal fun BannerRow(
    banner: TimelineItem.Banner,
    onAction: (ChatThreadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions: (@Composable RowScope.() -> Unit)? =
        when (banner.kind) {
            BannerKind.PAUSED ->
                {
                    {
                        Spacer(modifier = Modifier.weight(1f))
                        AutopButton(
                            text = stringResource(R.string.thread_action_continue),
                            onClick = { onAction(ChatThreadAction.Resume) },
                        )
                    }
                }
            BannerKind.BLOCKED ->
                {
                    {
                        Spacer(modifier = Modifier.weight(1f))
                        AutopTextButton(
                            text = stringResource(R.string.thread_action_end),
                            onClick = { onAction(ChatThreadAction.Stop) },
                            destructive = true,
                        )
                        AutopButton(
                            text = stringResource(R.string.thread_action_continue),
                            onClick = { onAction(ChatThreadAction.Resume) },
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

/** 等待回答卡（docs/tech/08 §4.2 AskCard）：问题 + 选项每行一个（AutopOptionRow，长文本自动换行）+ “自己输入”（卡片内独立输入框，N181）。 */
@Composable
internal fun AskCard(
    question: WaitingQuestion,
    onAnswer: (String) -> Unit,
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
        Text(text = question.question, style = MaterialTheme.typography.titleSmall)
        question.options.forEach { option ->
            AutopOptionRow(text = option, onClick = { onAnswer(option) })
        }
        if (selfInput) {
            AskInputRow(onAnswer = onAnswer)
        } else {
            AutopTextButton(
                text = stringResource(R.string.thread_ask_self),
                onClick = { selfInput = true },
            )
        }
    }
}

/** 「自己输入」独立输入行的最大行数（N181）。 */
private const val ASK_INPUT_MAX_LINES: Int = 3

/** 「自己输入」展开后的独立输入行（N181：不占用对话底部输入框）。 */
@Composable
private fun AskInputRow(
    onAnswer: (String) -> Unit,
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
            text = stringResource(R.string.thread_send),
            onClick = {
                if (text.isNotBlank()) onAnswer(text.trim())
            },
        )
    }
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
