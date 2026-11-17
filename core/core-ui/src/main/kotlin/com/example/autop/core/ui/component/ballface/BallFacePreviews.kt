/**
 * 表情矩阵预览（N247）：实色球、暗色主题与线条版，便于核对各状态的眼型/卫星/降透明。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.ui.component.ballface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.preview.PreviewTheme

private const val PREVIEW_COLUMNS = 4

private val previewMoods =
    listOf(
        BallFaceMood.IDLE,
        BallFaceMood.RUNNING_START,
        BallFaceMood.RUNNING,
        BallFaceMood.WAITING,
        BallFaceMood.PAUSED,
        BallFaceMood.BLOCKED,
        BallFaceMood.LOST,
        BallFaceMood.RECORDING,
        BallFaceMood.SUCCESS,
        BallFaceMood.FAILURE,
    )

@Composable
private fun FaceGrid(
    variant: BallFaceVariant,
    paint: BallFacePaint = BallFacePaint.THEME,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        previewMoods.chunked(PREVIEW_COLUMNS).forEach { rowMoods ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowMoods.forEach { mood ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AutopBallFace(
                            mood = mood,
                            variant = variant,
                            paint = paint,
                            modifier = Modifier.size(52.dp),
                        )
                        Text(
                            text = mood.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Ball face solid light", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
internal fun BallFaceSolidPreview() {
    PreviewTheme { FaceGrid(BallFaceVariant.SOLID) }
}

@Preview(name = "Ball face solid dark", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
internal fun BallFaceSolidDarkPreview() {
    PreviewTheme(dark = true) { FaceGrid(BallFaceVariant.SOLID) }
}

@Preview(name = "Ball face line", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
internal fun BallFaceLinePreview() {
    PreviewTheme { FaceGrid(BallFaceVariant.LINE) }
}
