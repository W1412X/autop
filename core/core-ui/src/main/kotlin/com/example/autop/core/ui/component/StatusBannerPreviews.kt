/** [StatusBanner] 亮/暗预览（docs/tech/08 §7）。 */
package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.core.ui.preview.PreviewData
import com.example.autop.core.ui.preview.PreviewTheme
import com.example.autop.core.ui.theme.StatusTone

@Preview(name = "light")
@Composable
internal fun StatusBannerPreview() =
    PreviewTheme {
        StatusBanner(
            tone = StatusTone.BLOCKED,
            title = PreviewData.TITLE,
            message = PreviewData.BODY,
            actions = { AutopTonalButton(text = PreviewData.BUTTON, onClick = {}) },
        )
    }

@Preview(name = "dark")
@Composable
internal fun StatusBannerDarkPreview() =
    PreviewTheme(dark = true) {
        StatusBanner(tone = StatusTone.PAUSED, message = PreviewData.BANNER)
    }
