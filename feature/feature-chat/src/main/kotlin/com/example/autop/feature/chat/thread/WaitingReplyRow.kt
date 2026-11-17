/**
 * 等待模型第一条输出的占位：发出消息后、模型还没有任何输出时显示，一张正在等待的小脸加一行提示，
 * 位置与助手消息一致（左侧）。表情用起跑态（卫星随球体主题色），不用“等待回答”，后者是 ask_user 语义。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.ballface.AutopBallFace
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.feature.chat.R

@Composable
internal fun WaitingReplyRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        AutopBallFace(
            mood = BallFaceMood.RUNNING_START,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(R.string.thread_waiting_reply),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
