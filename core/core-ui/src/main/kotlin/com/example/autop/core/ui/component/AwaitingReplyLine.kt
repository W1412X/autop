/**
 * 等待模型首个输出的占位行：一张起跑态表情加一行提示。对话页、流程与任务创建页共用同一组件，
 * 每次模型请求的等待期都显示它（口径见 agent-api 的 nextAwaitingModel）。
 * 表情用起跑态，不用“等待回答”（那是 ask_user 语义）。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.example.autop.core.ui.component.ballface.AutopBallFace
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.theme.LocalSpacing

/**
 * @param text 提示文案（调用方从资源取，组件本身不含文案）。
 * @param faceSize 起跑表情的边长，随所在页面的正文尺寸定。
 * @param textStyle 提示文字样式。
 */
@Composable
public fun AwaitingReplyLine(
    text: String,
    faceSize: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        AutopBallFace(mood = BallFaceMood.RUNNING_START, modifier = Modifier.size(faceSize))
        Text(
            text = text,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
