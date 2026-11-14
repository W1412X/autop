/**
 * 内嵌提问卡（docs/tech/08 §2 InlineQuestionCard）：对话时间线、任务进度悬浮窗、任务/流程创建页共用。
 * 问题 bodyMedium + 选项每行一个（[AutopOptionRow]）+ 「自己输入」（展开为独立输入行，发送图标在输入框内）。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.LocalSpacing

/**
 * 内嵌提问卡。
 *
 * @param interactive false 时只显示问题文字（回答后的历史卡片），选项与输入入口隐藏。
 * @param customLabel 「自己输入」入口文案（由调用方取 strings）。
 * @param customPlaceholder 展开后输入框的占位文案，空串不显示。
 * @param sendDescription 发送按钮的无障碍描述。
 * @param onAnswer 回答回调（选项文字或自由输入文字）。展开的输入框在回答后清空并收起。
 */
@Composable
public fun InlineQuestionCard(
    question: String,
    options: List<String>,
    onAnswer: (String) -> Unit,
    customLabel: String,
    sendDescription: String,
    modifier: Modifier = Modifier,
    customPlaceholder: String = "",
    interactive: Boolean = true,
) {
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
        Text(text = question, style = MaterialTheme.typography.bodyMedium)
        if (interactive) {
            options.forEach { option ->
                AutopOptionRow(text = option, onClick = { onAnswer(option) })
            }
            InlineCustomAnswer(
                label = customLabel,
                placeholder = customPlaceholder,
                sendDescription = sendDescription,
                onAnswer = onAnswer,
            )
        }
    }
}

/** 「自己输入」：收起时是一行选项，展开后是输入框（发送图标在框内），发送即作为回答。 */
@Composable
private fun InlineCustomAnswer(
    label: String,
    placeholder: String,
    sendDescription: String,
    onAnswer: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    if (!open) {
        AutopOptionRow(text = label, onClick = { open = true })
    } else {
        AutopInputBar(
            value = text,
            onValueChange = { text = it },
            spec = InputBarSpec(placeholder = placeholder, maxLines = INLINE_INPUT_MAX_LINES),
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                AutopIconButton(
                    icon = AutopIcons.Send,
                    contentDescription = sendDescription,
                    onClick = {
                        val answer = text.trim()
                        if (answer.isNotEmpty()) {
                            onAnswer(answer)
                            text = ""
                            open = false
                        }
                    },
                )
            },
        )
    }
}

/** 内嵌输入框的最大行数。 */
private const val INLINE_INPUT_MAX_LINES: Int = 3
