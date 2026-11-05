package com.example.autop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.autop.core.common.AnimationScaleReader
import com.example.autop.core.common.Capability
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.core.ui.theme.AutopTheme
import com.example.autop.core.ui.theme.toAppearanceSettings
import com.example.autop.feature.settings.debug.DebugPage
import dagger.hilt.android.AndroidEntryPoint
import java.util.Optional
import javax.inject.Inject

/**
 * 单 Activity 入口（docs/tech/01 §7）：组装配置、能力与主题，承载 [AppNavHost]。
 *
 * 外观来自 `ui.appearance`（改动经 ConfigProvider 即时生效）；动态取色按
 * [Capability.DYNAMIC_COLOR] 闸门；动效还受系统动画缩放约束（core-platform 读取）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var configProvider: ConfigProvider

    @Inject lateinit var capabilities: PlatformCapabilities

    @Inject lateinit var animationScaleReader: AnimationScaleReader

    /** 调试页（debug 源集实现；release 为 empty，docs/tech/08 §4.6）。 */
    @Inject lateinit var debugPage: Optional<DebugPage>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val snapshot by configProvider.observe().collectAsStateWithLifecycle()
            val appearance =
                snapshot[UiConfig.APPEARANCE].toAppearanceSettings(
                    dynamicColorSupported = capabilities.has(Capability.DYNAMIC_COLOR),
                    systemAnimationsEnabled = animationScaleReader.isEnabled(),
                )
            AutopTheme(appearance = appearance) {
                AppNavHost(
                    showLabels = snapshot[UiConfig.NAV].showLabels,
                    // 首次未完成引导进引导页（docs/tech/08 §4.7，N159）。
                    startDestination =
                        if (snapshot[UiConfig.ONBOARDING].completed) {
                            AutopRoutes.CHAT_LIST
                        } else {
                            AutopRoutes.ONBOARDING
                        },
                    debugPage = debugPage.orElse(null),
                )
            }
        }
    }
}
