/**
 * 主题入口（docs/tech/08 §1.5）：[AutopTheme] 按 [AppearanceSettings] 装配
 * MaterialTheme 与本地 CompositionLocal，根部铺 `background` 色。
 */
package com.example.autop.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.example.autop.core.ui.component.ballface.BallFaceColor
import com.example.autop.core.ui.component.ballface.BallFaceDirector
import com.example.autop.core.ui.component.ballface.BallFaceVariant

/**
 * 动效总开关。false 时所有动效静止（shimmer、悬浮球呼吸、展开动画等，
 * docs/tech/08 §6）：用户关闭动效或系统动画缩放为 0。
 */
public val LocalMotionEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/** 球体形态（设置页 `ui.appearance.ballShape`）：所有表情表面统一读取。 */
public val LocalBallShape: ProvidableCompositionLocal<BallFaceVariant> =
    staticCompositionLocalOf { BallFaceVariant.SOLID }

/** 悬浮球颜色（设置页 `ui.appearance.ballColor`）：所有表情表面统一读取。 */
public val LocalBallFaceColor: ProvidableCompositionLocal<BallFaceColor> =
    staticCompositionLocalOf { BallFaceColor.THEME }

/** 表情换脸间隔（设置页 `ui.appearance.petRerollMs`，毫秒）：多会话混合池与思考段共用。 */
public val LocalBallRerollMs: ProvidableCompositionLocal<Long> =
    staticCompositionLocalOf { BallFaceDirector.THINK_REROLL_MS }

/** 表情最短驻留（设置页 `ui.appearance.petFaceMinHoldMs`，毫秒，0 = 不驻留）：所有小脸共用。 */
public val LocalBallFaceMinHoldMs: ProvidableCompositionLocal<Long> =
    staticCompositionLocalOf { BallFaceDirector.MIN_HOLD_MS }

/**
 * 应用主题入口。[appearance.dynamicColor] 已由 app 根部按设备能力闸门
 * （[UiAppearanceSection.toAppearanceSettings]），为 true 时可安全取动态色板。
 */
@Composable
public fun AutopTheme(
    appearance: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    AutopThemeLocals(appearance) {
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

/**
 * 悬浮窗主题入口（N217）：与 [AutopTheme] 同款配色/排版/本地量，但**不铺背景 Surface**——
 * 悬浮窗窗口是透明的，铺底会把内容后面的应用界面糊住。
 */
@Composable
public fun AutopOverlayTheme(
    appearance: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    AutopThemeLocals(appearance, content)
}

/** 主题装配（色板 + MaterialTheme + 本地量）：[AutopTheme] 与 [AutopOverlayTheme] 共用。 */
@Composable
private fun AutopThemeLocals(
    appearance: AppearanceSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme =
        when (appearance.darkMode) {
            DarkMode.SYSTEM -> isSystemInDarkTheme()
            DarkMode.LIGHT -> false
            DarkMode.DARK -> true
        }
    val colorScheme =
        if (appearance.usesDynamicColor()) {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            when {
                appearance.customSeed != 0L ->
                    if (darkTheme) SeedPalette.dark(appearance.customSeed) else SeedPalette.light(appearance.customSeed)
                darkTheme -> SeedColors.dark(appearance.seedIndex)
                else -> SeedColors.light(appearance.seedIndex)
            }
        }
    CompositionLocalProvider(
        LocalSpacing provides spacingFor(appearance.density),
        LocalIconScale provides appearance.iconScale,
        LocalSemanticColors provides autopSemanticColors(colorScheme),
        LocalMotionEnabled provides appearance.motionEnabled,
        LocalBallShape provides appearance.ballShape,
        LocalBallFaceColor provides appearance.ballColor,
        LocalBallRerollMs provides appearance.petRerollMs,
        LocalBallFaceMinHoldMs provides appearance.petFaceMinHoldMs,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AutopTypography.base.scaled(appearance.fontScale),
            shapes = AutopShapes,
        ) {
            content()
        }
    }
}

private fun spacingFor(density: UiDensity): Spacing =
    when (density) {
        UiDensity.COMPACT -> Spacing.COMPACT
        UiDensity.STANDARD -> Spacing.STANDARD
        UiDensity.COMFORTABLE -> Spacing.COMFORTABLE
    }
