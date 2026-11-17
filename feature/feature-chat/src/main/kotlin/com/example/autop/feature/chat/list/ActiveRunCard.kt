/**
 * 运行中卡片（docs/tech/08 §4.1 ActiveRunCard）：StatusChip + 任务名 + 当前步骤 +
 * 3dp 不定进度条（工作中绕行；没有真实节点总量，不画步数比例）+ 停止图标按钮。
 */
package com.example.autop.feature.chat.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.ballface.AutopBallFace
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.example.autop.core.ui.component.ballface.BallFaceSpec
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.chat.R

/**
 * 运行中卡片。
 *
 * @param model 运行信息。
 * @param onStop 停止按钮回调。
 * @param modifier 组合修饰。
 */
@Composable
public fun ActiveRunCard(
    model: ActiveRunCardModel,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
                    shape = MaterialTheme.shapes.medium,
                ).padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutopBallFace(
            spec =
                BallFaceSpec(
                    mood = activeRunFaceOf(model.scene),
                    scene = model.scene,
                    seed = model.seed,
                ),
            modifier = Modifier.padding(end = spacing.md).size(FACE_SIZE),
        )
        Column(modifier = Modifier.weight(1f)) {
            StatusChip(text = stringResource(R.string.chat_status_running), tone = StatusTone.RUNNING)
            Text(
                text = model.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (model.currentStep != null) {
                Text(
                    text = model.currentStep,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .padding(top = spacing.sm)
                        .fillMaxWidth()
                        .height(AutopDimens.progressBarHeight),
            )
        }
        AutopIconButton(
            icon = AutopIcons.Stop,
            contentDescription = stringResource(R.string.chat_stop),
            onClick = onStop,
        )
    }
}

/** 运行卡片的表情：起跑场景用起跑脸，其余为运行脸（场景决定表演池）。 */
internal fun activeRunFaceOf(scene: BallFaceScene): BallFaceMood =
    if (scene == BallFaceScene.STARTING) BallFaceMood.RUNNING_START else BallFaceMood.RUNNING

/** 运行脸尺寸（设计稿 P1-7：32dp）。 */
private val FACE_SIZE = 32.dp
