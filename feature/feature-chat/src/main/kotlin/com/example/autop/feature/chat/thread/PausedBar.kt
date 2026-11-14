/**
 * 运行暂停时显示在输入区上方的状态条：说明文字 + 继续按钮。
 * 只在暂停未恢复时出现，不进入消息列表（恢复后随之消失，列表里只留历史分割线）。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

/** 暂停条：[detail] 为空时显示默认文案；[onResume] 触发继续运行。 */
@Composable
internal fun PausedBar(
    detail: String?,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = detail ?: stringResource(R.string.chat_paused_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AutopButton(
            text = stringResource(R.string.chat_paused_continue),
            onClick = onResume,
        )
    }
}
