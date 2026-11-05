/**
 * 预设主题色（docs/tech/08 §1.1）：青柠（默认，即基线）、湖蓝、暖橙、紫罗兰、石墨。
 *
 * 每组只在基线方案上 `copy()` 覆盖 primary / onPrimary / primaryContainer /
 * onPrimaryContainer / secondary / secondaryContainer / tertiary，取值从 LimeTV
 * `core/designsystem/theme/Color.kt` 原样复制；索引与设置页“主题色”选项一一对应。
 */
package com.example.autop.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** 预设主题色查表。 */
public object SeedColors {
    /** 预设数量（设置页选项数）。 */
    public const val COUNT: Int = 5

    private const val SEED_LIME: Int = 0
    private const val SEED_BLUE: Int = 1
    private const val SEED_ORANGE: Int = 2
    private const val SEED_VIOLET: Int = 3
    private const val SEED_GRAPHITE: Int = 4

    /** [seedIndex] 对应的浅色方案（越界抛 [IllegalStateException]）。 */
    public fun light(seedIndex: Int): ColorScheme =
        when (seedIndex) {
            SEED_LIME -> AutopLightColors
            SEED_BLUE -> BlueLightColors
            SEED_ORANGE -> OrangeLightColors
            SEED_VIOLET -> VioletLightColors
            SEED_GRAPHITE -> GraphiteLightColors
            else -> error("unknown seedIndex $seedIndex")
        }

    /** [seedIndex] 对应的深色方案（越界抛 [IllegalStateException]）。 */
    public fun dark(seedIndex: Int): ColorScheme =
        when (seedIndex) {
            SEED_LIME -> AutopDarkColors
            SEED_BLUE -> BlueDarkColors
            SEED_ORANGE -> OrangeDarkColors
            SEED_VIOLET -> VioletDarkColors
            SEED_GRAPHITE -> GraphiteDarkColors
            else -> error("unknown seedIndex $seedIndex")
        }
}

// ── 1 · 湖蓝 ────────────────────────────────────────────────────

private val BlueLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF2F5FA5),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD5E3FF),
        onPrimaryContainer = Color(0xFF001B3D),
        secondary = Color(0xFF545F71),
        secondaryContainer = Color(0xFFD8E3F8),
        tertiary = Color(0xFF6C5677),
    )

private val BlueDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFFA9C7FF),
        onPrimary = Color(0xFF00305F),
        primaryContainer = Color(0xFF134786),
        onPrimaryContainer = Color(0xFFD5E3FF),
        secondary = Color(0xFFBCC7DB),
        secondaryContainer = Color(0xFF3C4758),
        tertiary = Color(0xFFD8BDE4),
    )

// ── 2 · 暖橙 ────────────────────────────────────────────────────

private val OrangeLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF8F4C1E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBC8),
        onPrimaryContainer = Color(0xFF331200),
        secondary = Color(0xFF755846),
        secondaryContainer = Color(0xFFFFDBC8),
        tertiary = Color(0xFF5F6236),
    )

private val OrangeDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFFFFB68C),
        onPrimary = Color(0xFF522300),
        primaryContainer = Color(0xFF723508),
        onPrimaryContainer = Color(0xFFFFDBC8),
        secondary = Color(0xFFE5BFA8),
        secondaryContainer = Color(0xFF5B4130),
        tertiary = Color(0xFFC8CB8F),
    )

// ── 3 · 紫罗兰 ──────────────────────────────────────────────────

private val VioletLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF5F41A8),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE8DDFF),
        onPrimaryContainer = Color(0xFF1C0060),
        secondary = Color(0xFF605B71),
        secondaryContainer = Color(0xFFE6DFF9),
        tertiary = Color(0xFF7C5265),
    )

private val VioletDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFFCFBDFF),
        onPrimary = Color(0xFF330F77),
        primaryContainer = Color(0xFF47298F),
        onPrimaryContainer = Color(0xFFE8DDFF),
        secondary = Color(0xFFCAC3DC),
        secondaryContainer = Color(0xFF484459),
        tertiary = Color(0xFFEDB8CD),
    )

// ── 4 · 石墨 ────────────────────────────────────────────────────

private val GraphiteLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF4A5C61),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCDE7ED),
        onPrimaryContainer = Color(0xFF051F23),
        secondary = Color(0xFF4C6266),
        secondaryContainer = Color(0xFFCFE6EA),
        tertiary = Color(0xFF545D7E),
    )

private val GraphiteDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFFB1CBD1),
        onPrimary = Color(0xFF1C343A),
        primaryContainer = Color(0xFF334A50),
        onPrimaryContainer = Color(0xFFCDE7ED),
        secondary = Color(0xFFB3CBCF),
        secondaryContainer = Color(0xFF344A4E),
        tertiary = Color(0xFFBCC5EB),
    )
