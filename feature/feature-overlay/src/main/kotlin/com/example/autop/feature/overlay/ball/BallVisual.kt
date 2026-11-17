/**
 * 悬浮球视觉（docs/tech/08 §5.1）：球体即表情（[AutopBallFace]），形态读 `ui.appearance.ballShape`，
 * 颜色读 `ui.appearance.ballColor`（经主题下发，ADR-0045）。
 * 不叠状态环、不画光晕：状态语义交给表情与卫星符号。
 * 动效受 `ui.appearance.motionEnabled` 与 [LocalMotionEnabled] 控制。
 */
package com.example.autop.feature.overlay.ball

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.component.ballface.AutopBallFace
import com.example.autop.core.ui.component.ballface.BallFaceMood
import com.example.autop.core.ui.component.ballface.BallFaceScene
import com.example.autop.core.ui.component.ballface.BallFaceSpec
import com.example.autop.core.ui.theme.LocalMotionEnabled

/** 球直径默认值（dp，`ui.overlay.ballSizeDp` 的默认）。 */
internal const val DEFAULT_BALL_SIZE_DP: Int = 44

/** 球窗口余量与球直径的比例（原设计 12dp / 52dp，等比保留）。 */
private const val HALO_PAD_RATIO: Float = 12f / 52f

/** 表情画布与球直径的比例：设计稿 1408 视角中球体占 72.7%，画布 = 球 × 1.375。 */
private const val FACE_CANVAS_RATIO: Float = 1.375f

/**
 * 球的几何：窗口、表情画布都由球直径推出，直径改了整体等比缩放。
 */
internal class BallDims(
    val ballDp: Dp,
) {
    /** 窗口每侧余量：卫星会探出球面，需要留空，否则被窗口裁切。 */
    val haloPad: Dp = ballDp * HALO_PAD_RATIO

    /** 球窗口尺寸：球加两侧余量（窗口 spec 用）。 */
    val windowDp: Dp = ballDp + haloPad * 2

    /** 表情画布尺寸。 */
    val faceDp: Dp = ballDp * FACE_CANVAS_RATIO
}

/** 当前球的几何：由球窗口内容根部提供（直径来自 `ui.overlay.ballSizeDp`）。 */
internal val LocalBallDims: ProvidableCompositionLocal<BallDims> =
    staticCompositionLocalOf { BallDims(DEFAULT_BALL_SIZE_DP.dp) }

/** 隐身态细条宽（贴屏幕缘的胶囊，「特别小」）。 */
internal val SLIVER_WIDTH = 3.dp

/** 隐身态细条高（居中，抓取/点按余量在窗口本身）。 */
private val SLIVER_HEIGHT = 48.dp

/** 球视觉：表情即球体（形态与动效由设置决定）。 */
@Composable
internal fun BallVisual(
    uiState: BallUiState,
    modifier: Modifier = Modifier,
) {
    val motionOn = uiState.motionEnabled && LocalMotionEnabled.current
    val running = uiState.mode as? BallMode.Running
    val multi = uiState.sessionCount > 1
    // 数字：运行中在卫星上，其它状态在角标上；拦截/断开等不显示数字（状态语义优先）。
    val count = uiState.sessionCount.takeIf { it > 0 && uiState.mode != BallMode.AccessibilityLost }
    val dims = LocalBallDims.current
    Box(modifier = modifier.size(dims.windowDp)) {
        AutopBallFace(
            spec =
                BallFaceSpec(
                    // 多会话时表情统一从混合池抽取，不再随首步切到起跑脸。
                    mood = if (running != null && multi) BallFaceMood.RUNNING else ballFaceMoodOf(uiState.mode),
                    variant = uiState.ballShape,
                    scene = running?.let { ballFaceSceneOf(it, uiState.sessionCount) } ?: BallFaceScene.SAMPLING,
                    seed = running?.seed ?: 0L,
                    motionEnabled = motionOn,
                    count = count,
                ),
            modifier = Modifier.align(Alignment.Center).size(dims.faceDp),
        )
    }
}

/** 隐身态细条（3dp×48dp 胶囊，贴屏幕缘，主题主色）。 */
@Composable
internal fun BallSliver(
    side: BallSide,
    modifier: Modifier = Modifier,
) {
    val dims = LocalBallDims.current
    Box(modifier = modifier.size(dims.windowDp)) {
        Box(modifier = Modifier.align(Alignment.Center).size(dims.ballDp)) {
            Box(
                modifier =
                    Modifier
                        .align(if (side == BallSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart)
                        .size(width = SLIVER_WIDTH, height = SLIVER_HEIGHT)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(SLIVER_WIDTH / 2)),
            )
        }
    }
}
