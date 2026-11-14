/**
 * 等待回答卡（docs/tech/08 §4.2 AskCard）：对话页的薄封装，渲染交给 core-ui 的 [InlineQuestionCard]，
 * 与悬浮窗、任务/流程创建页的提问卡同一套样式（N181 “自己输入” 独立输入行）。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.InlineQuestionCard
import com.example.autop.feature.chat.R

/** 等待回答卡：问题 + 选项每行一个 + “自己输入”。 */
@Composable
internal fun AskCard(
    question: WaitingQuestion,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InlineQuestionCard(
        question = question.question,
        options = question.options,
        onAnswer = onAnswer,
        customLabel = stringResource(R.string.thread_ask_self),
        sendDescription = stringResource(R.string.thread_send),
        modifier = modifier,
    )
}
