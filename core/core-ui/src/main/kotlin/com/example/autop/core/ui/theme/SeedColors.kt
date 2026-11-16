/**
 * 预设主题色（docs/tech/08 §1.1）：青柠（默认，即基线）、湖蓝、薄荷、可可、紫罗兰、玫瑰。
 *
 * 青柠、湖蓝、紫罗兰的取值从 LimeTV `core/designsystem/theme/Color.kt` 原样复制
 * （在基线上 `copy()` 覆盖 primary / onPrimary / primaryContainer /
 * onPrimaryContainer / secondary / secondaryContainer / tertiary）；薄荷、可可、玫瑰
 * 按同一 M3 色调梯度推导（低饱和容器 + 中性表面，避免刺眼）。索引与设置页“主题色”
 * 选项一一对应，须连续且与 [COUNT] 一致。调色盘自选的任意色走 [SeedPalette] 动态推导。
 */
package com.example.autop.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** 预设主题色查表。 */
public object SeedColors {
    /** 预设数量（设置页选项数）。 */
    public const val COUNT: Int = 6

    private const val SEED_LIME: Int = 0
    private const val SEED_BLUE: Int = 1
    private const val SEED_MINT: Int = 2
    private const val SEED_COCOA: Int = 3
    private const val SEED_VIOLET: Int = 4
    private const val SEED_ROSE: Int = 5

    /** [seedIndex] 对应的浅色方案（越界抛 [IllegalStateException]）。 */
    public fun light(seedIndex: Int): ColorScheme =
        when (seedIndex) {
            SEED_LIME -> AutopLightColors
            SEED_BLUE -> BlueLightColors
            SEED_MINT -> MintLightColors
            SEED_COCOA -> CocoaLightColors
            SEED_VIOLET -> VioletLightColors
            SEED_ROSE -> RoseLightColors
            else -> error("unknown seedIndex $seedIndex")
        }

    /** [seedIndex] 对应的深色方案（越界抛 [IllegalStateException]）。 */
    public fun dark(seedIndex: Int): ColorScheme =
        when (seedIndex) {
            SEED_LIME -> AutopDarkColors
            SEED_BLUE -> BlueDarkColors
            SEED_MINT -> MintDarkColors
            SEED_COCOA -> CocoaDarkColors
            SEED_VIOLET -> VioletDarkColors
            SEED_ROSE -> RoseDarkColors
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

// ── 2 · 薄荷（柔和青绿）─────────────────────────────────────────

private val MintLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF2E6A5F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB3F0E2),
        onPrimaryContainer = Color(0xFF00201B),
        secondary = Color(0xFF4A635C),
        secondaryContainer = Color(0xFFCCE8E0),
        tertiary = Color(0xFF3D6374),
    )

private val MintDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFF97D3C5),
        onPrimary = Color(0xFF003730),
        primaryContainer = Color(0xFF145248),
        onPrimaryContainer = Color(0xFFB3F0E2),
        secondary = Color(0xFFB1CCC4),
        secondaryContainer = Color(0xFF324B45),
        tertiary = Color(0xFFA5CCDF),
    )

// ── 3 · 可可（柔和暖棕）─────────────────────────────────────────

private val CocoaLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF7A5842),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF7DDCB),
        onPrimaryContainer = Color(0xFF2C1608),
        secondary = Color(0xFF6B5B50),
        secondaryContainer = Color(0xFFF1DFD4),
        tertiary = Color(0xFF5D6241),
    )

private val CocoaDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFFE5BFA5),
        onPrimary = Color(0xFF452A17),
        primaryContainer = Color(0xFF5F4130),
        onPrimaryContainer = Color(0xFFF7DDCB),
        secondary = Color(0xFFD7C2B7),
        secondaryContainer = Color(0xFF52443A),
        tertiary = Color(0xFFC6CA9F),
    )

// ── 4 · 紫罗兰 ──────────────────────────────────────────────────

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

// ── 5 · 玫瑰（柔和粉紫）─────────────────────────────────────────

private val RoseLightColors: ColorScheme =
    AutopLightColors.copy(
        primary = Color(0xFF8E4A5E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9E2),
        onPrimaryContainer = Color(0xFF3A071A),
        secondary = Color(0xFF74565D),
        secondaryContainer = Color(0xFFF2DDE2),
        tertiary = Color(0xFF7C5265),
    )

private val RoseDarkColors: ColorScheme =
    AutopDarkColors.copy(
        primary = Color(0xFFFFB1C4),
        onPrimary = Color(0xFF551D31),
        primaryContainer = Color(0xFF703446),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = Color(0xFFE2BDC5),
        secondaryContainer = Color(0xFF5A3F45),
        tertiary = Color(0xFFEDB8CD),
    )
