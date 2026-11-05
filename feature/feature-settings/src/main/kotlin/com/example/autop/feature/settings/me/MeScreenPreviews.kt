/**
 * MeScreen 预览（docs/tech/08 §7：每个页面亮色、暗色各一个）。
 */
package com.example.autop.feature.settings.me

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewTheme

@Preview(name = "Me light")
@Composable
internal fun MeScreenLightPreview() {
    PreviewTheme {
        MeScreen(state = MeUiState(), onAction = {})
    }
}

@Preview(name = "Me dark")
@Composable
internal fun MeScreenDarkPreview() {
    PreviewTheme(dark = true) {
        MeScreen(state = MeUiState(), onAction = {})
    }
}
