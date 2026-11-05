/**
 * 预览主题包装（docs/tech/08 §7）：公共组件与页面的亮/暗 `@Preview` 统一走这里。
 */
package com.example.autop.core.ui.preview

import androidx.compose.runtime.Composable
import com.example.autop.core.ui.theme.AppearanceSettings
import com.example.autop.core.ui.theme.AutopTheme
import com.example.autop.core.ui.theme.DarkMode

/** 亮（默认）或暗色的 [AutopTheme] 包装。 */
@Composable
public fun PreviewTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    AutopTheme(
        appearance =
            AppearanceSettings(
                darkMode = if (dark) DarkMode.DARK else DarkMode.LIGHT,
            ),
        content = content,
    )
}
