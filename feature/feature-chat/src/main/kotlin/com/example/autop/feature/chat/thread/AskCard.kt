/**
 * 时间线卡片渲染（docs/tech/08 §4.2）：暂停 / 拦截 / 中断 [StatusBanner]、
 * 等待回答卡（AskCard）；Run 元信息行见 RunMetaRow（N183）。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.example.autop.core.ui.component.AutopInputBar
import com.example.autop.core.ui.component.AutopOptionRow
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.InputBarSpec
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

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
