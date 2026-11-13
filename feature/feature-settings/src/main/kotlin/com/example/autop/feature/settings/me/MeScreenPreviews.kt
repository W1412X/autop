/**
 * MeScreen 预览（docs/tech/08 §7：每个页面亮色、暗色各一个）。
 */
package com.example.autop.feature.settings.me

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.feature.settings.MeGroup

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

/** 二级页（权限与运行）：亮色、暗色各一个。 */
@Preview(name = "MeGroup runtime light")
@Composable
internal fun MeGroupScreenLightPreview() {
    PreviewTheme {
        MeGroupScreen(
            group = MeGroup.PERMISSIONS_RUNTIME,
            state = MeUiState(),
            focus = null,
            onBack = {},
            onAction = {},
        )
    }
}

@Preview(name = "MeGroup runtime dark")
@Composable
internal fun MeGroupScreenDarkPreview() {
    PreviewTheme(dark = true) {
        MeGroupScreen(
            group = MeGroup.PERMISSIONS_RUNTIME,
            state = MeUiState(),
            focus = null,
            onBack = {},
            onAction = {},
        )
    }
}

/** 动作后等待弹层（tool_optimize 03 §5.1）：亮色、暗色各一个。 */
@Preview(name = "ActionDelay light")
@Composable
internal fun ActionDelaySheetLightPreview() {
    PreviewTheme {
        ActionDelaySheet(selectedMs = 5000, onSelect = {}, onDismiss = {})
    }
}

@Preview(name = "ActionDelay dark")
@Composable
internal fun ActionDelaySheetDarkPreview() {
    PreviewTheme(dark = true) {
        ActionDelaySheet(selectedMs = 5000, onSelect = {}, onDismiss = {})
    }
}
