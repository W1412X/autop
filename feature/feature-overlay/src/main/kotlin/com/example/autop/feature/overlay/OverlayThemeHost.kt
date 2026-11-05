/**
 * 悬浮窗主题源（N217）：悬浮窗内容经 [Wrap] 套上与应用一致的主题（`ui.appearance` 即时生效，
 * 深浅模式跟随应用设置），由 [composeOverlayView] 在内容入口统一包裹。
 */
package com.example.autop.feature.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.autop.core.common.AnimationScaleReader
import com.example.autop.core.common.Capability
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.ui.theme.AutopOverlayTheme
import com.example.autop.core.ui.theme.toAppearanceSettings
import javax.inject.Inject
import javax.inject.Singleton

/** 悬浮窗主题源：外观映射与 `MainActivity` 同款（能力闸门 + 系统动效约束）。 */
@Singleton
public class OverlayThemeHost
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val capabilities: PlatformCapabilities,
        private val animationScaleReader: AnimationScaleReader,
    ) {
        /** 悬浮窗内容的主题包裹（跟随配置变化重组）。 */
        @Composable
        public fun Wrap(content: @Composable () -> Unit) {
            val snapshot by configProvider.observe().collectAsState()
            val appearance =
                snapshot[UiConfig.APPEARANCE].toAppearanceSettings(
                    dynamicColorSupported = capabilities.has(Capability.DYNAMIC_COLOR),
                    systemAnimationsEnabled = animationScaleReader.isEnabled(),
                )
            AutopOverlayTheme(appearance = appearance, content = content)
        }
    }
