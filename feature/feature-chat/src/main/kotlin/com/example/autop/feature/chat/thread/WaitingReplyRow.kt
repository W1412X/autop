/**
 * 等待模型首个输出的占位（对话页）：发出消息后、本次模型请求还没有任何输出时显示，位置与助手消息一致（左侧）。
 * 具体样式与表情由 core-ui 的 [AwaitingReplyLine] 统一提供。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AwaitingReplyLine
import com.example.autop.feature.chat.R

@Composable
internal fun WaitingReplyRow(modifier: Modifier = Modifier) {
    AwaitingReplyLine(
        text = stringResource(R.string.thread_waiting_reply),
        faceSize = 32.dp,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}
