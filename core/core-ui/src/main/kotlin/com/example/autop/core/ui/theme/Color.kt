/**
 * 颜色 token（docs/tech/08 §1.1）。
 *
 * 基线色板取 LimeTV `core/designsystem/theme/Color.kt` 原值；LimeTV 未定义、
 * 导致 Chip 边框与对话框/底部弹层回落 M3 默认紫灰色的槽位按同色系补齐。
 * 业务代码不允许出现颜色字面量，一律取本文件与 [SeedColors] 的值。
 */
package com.example.autop.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── 基线色板（LimeTV 原值）─────────────────────────────────────────

private val LimePrimaryLight = Color(0xFF4C662B)
private val LimeOnPrimaryLight = Color(0xFFFFFFFF)
private val LimePrimaryContainerLight = Color(0xFFCDEDA3)
private val LimeOnPrimaryContainerLight = Color(0xFF102000)
private val LimeSecondaryLight = Color(0xFF586249)
private val LimeOnSecondaryLight = Color(0xFFFFFFFF)
private val LimeSecondaryContainerLight = Color(0xFFDCE7C7)
private val LimeOnSecondaryContainerLight = Color(0xFF151E0B)
private val LimeTertiaryLight = Color(0xFF386663)
private val LimeOnTertiaryLight = Color(0xFFFFFFFF)
private val LimeErrorLight = Color(0xFFBA1A1A)
private val LimeOnErrorLight = Color(0xFFFFFFFF)
private val LimeBackgroundLight = Color(0xFFF9FAEF)
private val LimeOnBackgroundLight = Color(0xFF1A1C16)
private val LimeSurfaceVariantLight = Color(0xFFE1E4D5)
private val LimeOnSurfaceVariantLight = Color(0xFF44483D)
private val LimeOutlineLight = Color(0xFF75796C)

private val LimePrimaryDark = Color(0xFFB1D18A)
private val LimeOnPrimaryDark = Color(0xFF1F3701)
private val LimePrimaryContainerDark = Color(0xFF354E15)
private val LimeOnPrimaryContainerDark = Color(0xFFCDEDA3)
private val LimeSecondaryDark = Color(0xFFBFCBAC)
private val LimeOnSecondaryDark = Color(0xFF2A331F)
private val LimeSecondaryContainerDark = Color(0xFF404A34)
private val LimeOnSecondaryContainerDark = Color(0xFFDCE7C7)
private val LimeTertiaryDark = Color(0xFFA0D0CC)
private val LimeOnTertiaryDark = Color(0xFF003735)
private val LimeErrorDark = Color(0xFFFFB4AB)
private val LimeOnErrorDark = Color(0xFF690005)
private val LimeBackgroundDark = Color(0xFF12140E)
private val LimeOnBackgroundDark = Color(0xFFE2E3D8)
private val LimeSurfaceVariantDark = Color(0xFF44483D)
private val LimeOnSurfaceVariantDark = Color(0xFFC5C8BA)
private val LimeOutlineDark = Color(0xFF8F9285)

// ── 本项目补齐的 M3 槽位（docs/tech/08 §1.1 第二张表）────────────────

private val OutlineVariantLight = Color(0xFFC5C8BA)
private val TertiaryContainerLight = Color(0xFFBCECE7)
private val OnTertiaryContainerLight = Color(0xFF00201E)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val SurfaceDimLight = Color(0xFFDADBD0)
private val SurfaceBrightLight = Color(0xFFF9FAEF)
private val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
private val SurfaceContainerLowLight = Color(0xFFF3F4E9)
private val SurfaceContainerLight = Color(0xFFEEEFE3)
private val SurfaceContainerHighLight = Color(0xFFE8E9DE)
private val SurfaceContainerHighestLight = Color(0xFFE2E3D8)
private val InverseSurfaceLight = Color(0xFF2F312A)
private val InverseOnSurfaceLight = Color(0xFFF1F2E6)
private val InversePrimaryLight = Color(0xFFB1D18A)

private val OutlineVariantDark = Color(0xFF44483D)
private val TertiaryContainerDark = Color(0xFF1F4E4B)
private val OnTertiaryContainerDark = Color(0xFFBCECE7)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)
private val SurfaceDimDark = Color(0xFF12140E)
private val SurfaceBrightDark = Color(0xFF383A32)
private val SurfaceContainerLowestDark = Color(0xFF0C0F09)
private val SurfaceContainerLowDark = Color(0xFF1A1C16)
private val SurfaceContainerDark = Color(0xFF1E201A)
private val SurfaceContainerHighDark = Color(0xFF282B24)
private val SurfaceContainerHighestDark = Color(0xFF33362E)
private val InverseSurfaceDark = Color(0xFFE2E3D8)
private val InverseOnSurfaceDark = Color(0xFF2F312A)
private val InversePrimaryDark = Color(0xFF4C662B)

private val Scrim = Color(0xFF000000)

/** 浅色基线方案（青柠，M3 全槽位）。 */
public val AutopLightColors: ColorScheme =
    lightColorScheme(
        primary = LimePrimaryLight,
        onPrimary = LimeOnPrimaryLight,
        primaryContainer = LimePrimaryContainerLight,
        onPrimaryContainer = LimeOnPrimaryContainerLight,
        inversePrimary = InversePrimaryLight,
        secondary = LimeSecondaryLight,
        onSecondary = LimeOnSecondaryLight,
        secondaryContainer = LimeSecondaryContainerLight,
        onSecondaryContainer = LimeOnSecondaryContainerLight,
        tertiary = LimeTertiaryLight,
        onTertiary = LimeOnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        background = LimeBackgroundLight,
        onBackground = LimeOnBackgroundLight,
        surface = LimeBackgroundLight,
        onSurface = LimeOnBackgroundLight,
        surfaceVariant = LimeSurfaceVariantLight,
        onSurfaceVariant = LimeOnSurfaceVariantLight,
        surfaceDim = SurfaceDimLight,
        surfaceBright = SurfaceBrightLight,
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
        inverseSurface = InverseSurfaceLight,
        inverseOnSurface = InverseOnSurfaceLight,
        outline = LimeOutlineLight,
        outlineVariant = OutlineVariantLight,
        error = LimeErrorLight,
        onError = LimeOnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
        scrim = Scrim,
    )

/** 深色基线方案（青柠，M3 全槽位）。 */
public val AutopDarkColors: ColorScheme =
    darkColorScheme(
        primary = LimePrimaryDark,
        onPrimary = LimeOnPrimaryDark,
        primaryContainer = LimePrimaryContainerDark,
        onPrimaryContainer = LimeOnPrimaryContainerDark,
        inversePrimary = InversePrimaryDark,
        secondary = LimeSecondaryDark,
        onSecondary = LimeOnSecondaryDark,
        secondaryContainer = LimeSecondaryContainerDark,
        onSecondaryContainer = LimeOnSecondaryContainerDark,
        tertiary = LimeTertiaryDark,
        onTertiary = LimeOnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        background = LimeBackgroundDark,
        onBackground = LimeOnBackgroundDark,
        surface = LimeBackgroundDark,
        onSurface = LimeOnBackgroundDark,
        surfaceVariant = LimeSurfaceVariantDark,
        onSurfaceVariant = LimeOnSurfaceVariantDark,
        surfaceDim = SurfaceDimDark,
        surfaceBright = SurfaceBrightDark,
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
        inverseSurface = InverseSurfaceDark,
        inverseOnSurface = InverseOnSurfaceDark,
        outline = LimeOutlineDark,
        outlineVariant = OutlineVariantDark,
        error = LimeErrorDark,
        onError = LimeOnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
        scrim = Scrim,
    )

// ── 语义色与透明度 token ─────────────────────────────────────────

/** 透明度 token（docs/tech/08 §1.1，对应 LimeTV 的 #CC000000 用法）。 */
public object AutopAlpha {
    /** 半透明底块（surfaceVariant 底等）。 */
    public const val BLOCK_FILL: Float = 0.55f

    /** 选项按钮行主题色半透明底（未选中）。 */
    public const val OPTION_FILL: Float = 0.1f

    /** 选项按钮行主题色半透明底（选中）。 */
    public const val OPTION_FILL_SELECTED: Float = 0.2f

    /** 禁用态底块。 */
    public const val BLOCK_FILL_DISABLED: Float = 0.35f

    /** 禁用态内容。 */
    public const val DISABLED_CONTENT: Float = 0.4f

    /** 角标文字。 */
    public const val BADGE_TEXT: Float = 0.75f

    /** 悬浮层遮罩。 */
    public const val OVERLAY_SCRIM: Float = 0.8f

    /** shimmer 高光线（扫过底色）。 */
    public const val SHIMMER_HIGHLIGHT: Float = 0.7f
}

/** 单一语义的前景/底色对（只映射到 scheme 槽位，不新增色值）。 */
@Immutable
public data class SemanticTone(
    public val foreground: Color,
    public val container: Color,
)

/**
 * 状态语义键（StatusChip 等组件使用）。
 * `BLOCKED` 含 failed；`NEUTRAL` 含 cancelled / expired / interrupted。
 */
public enum class StatusTone {
    RUNNING,
    WAITING,
    PAUSED,
    BLOCKED,
    SUCCEEDED,
    NEUTRAL,
}

/** 语义色集（docs/tech/08 §1.1 语义色表），通过 [LocalSemanticColors] 提供。 */
@Immutable
public data class AutopSemanticColors(
    public val running: SemanticTone,
    public val waiting: SemanticTone,
    public val paused: SemanticTone,
    public val blocked: SemanticTone,
    public val succeeded: SemanticTone,
    public val neutral: SemanticTone,
)

/** 由 [scheme] 槽位推导语义色。 */
public fun autopSemanticColors(scheme: ColorScheme): AutopSemanticColors =
    AutopSemanticColors(
        running = SemanticTone(foreground = scheme.primary, container = scheme.primaryContainer),
        waiting = SemanticTone(foreground = scheme.tertiary, container = scheme.tertiaryContainer),
        paused = SemanticTone(foreground = scheme.secondary, container = scheme.secondaryContainer),
        blocked = SemanticTone(foreground = scheme.error, container = scheme.errorContainer),
        succeeded =
            SemanticTone(
                foreground = scheme.primary,
                container = scheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
            ),
        neutral =
            SemanticTone(
                foreground = scheme.onSurfaceVariant,
                container = scheme.surfaceVariant.copy(alpha = AutopAlpha.BLOCK_FILL),
            ),
    )

/** 取 [status] 对应的语义色。 */
public fun AutopSemanticColors.tone(status: StatusTone): SemanticTone =
    when (status) {
        StatusTone.RUNNING -> running
        StatusTone.WAITING -> waiting
        StatusTone.PAUSED -> paused
        StatusTone.BLOCKED -> blocked
        StatusTone.SUCCEEDED -> succeeded
        StatusTone.NEUTRAL -> neutral
    }

/** 语义色入口（[AutopTheme] 提供）。 */
public val LocalSemanticColors = staticCompositionLocalOf { autopSemanticColors(AutopLightColors) }
