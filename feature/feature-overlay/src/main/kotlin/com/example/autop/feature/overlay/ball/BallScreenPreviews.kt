package com.example.autop.feature.overlay.ball

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.theme.AppearanceSettings
import com.example.autop.core.ui.theme.AutopTheme
import com.example.autop.core.ui.theme.DarkMode

private val previewCallbacks =
    object : BallCallbacks {
        override fun onMove(
            x: Int,
            y: Int,
        ) = Unit

        override fun onDragEnd(
            ballSizePx: Int,
            screenWidthPx: Int,
            sliverPx: Int,
            magnetPx: Int,
        ) = Unit

        override fun onFirstLayout(
            screenWidthPx: Int,
            screenHeightPx: Int,
            ballSizePx: Int,
            sliverPx: Int,
        ) = Unit

        override fun onTap(screenWidthPx: Int) = Unit

        override fun onReveal(
            screenWidthPx: Int,
            ballSizePx: Int,
        ) = Unit

        override fun onDoubleTap() = Unit

        override fun onAccessibilityTap() = Unit
    }

@Composable
private fun BallPreviewContent(
    uiState: BallUiState,
    appearance: AppearanceSettings = AppearanceSettings(),
) = AutopTheme(appearance) {
    Box(Modifier.padding(24.dp)) {
        BallScreen(uiState, BallPosition(0, 0), previewCallbacks)
    }
}

@Preview(name = "Ball idle light", showBackground = true)
@Composable
internal fun BallIdleLightPreview() = BallPreviewContent(BallUiState(mode = BallMode.Idle))

@Preview(name = "Ball idle dark", showBackground = true)
@Composable
internal fun BallIdleDarkPreview() =
    BallPreviewContent(BallUiState(mode = BallMode.Idle), AppearanceSettings(darkMode = DarkMode.DARK))

@Preview(name = "Ball running", showBackground = true)
@Composable
internal fun BallRunningPreview() =
    BallPreviewContent(
        BallUiState(mode = BallMode.Running(stepCount = 12, progress = 0.3f, toolCategory = ToolCategory.GESTURE)),
    )

@Preview(name = "Ball waiting", showBackground = true)
@Composable
internal fun BallWaitingPreview() = BallPreviewContent(BallUiState(mode = BallMode.WaitingAnswer))

@Preview(name = "Ball paused", showBackground = true)
@Composable
internal fun BallPausedPreview() = BallPreviewContent(BallUiState(mode = BallMode.Paused))

@Preview(name = "Ball blocked", showBackground = true)
@Composable
internal fun BallBlockedPreview() = BallPreviewContent(BallUiState(mode = BallMode.Blocked))

@Preview(name = "Ball accessibility lost", showBackground = true)
@Composable
internal fun BallAccessibilityLostPreview() = BallPreviewContent(BallUiState(mode = BallMode.AccessibilityLost))
