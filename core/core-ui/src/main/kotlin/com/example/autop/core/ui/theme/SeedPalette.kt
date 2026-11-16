/**
 * 调色盘自定义主题色 → M3 色板（docs/tech/08 §1.1）。
 *
 * 用户选的种子色只提供色相/饱和度/明度倾向：主色、容器与 on 色按固定 M3 色调梯度
 * 重新推导，保证任意取色下正文对比度达标、容器柔和不刺眼；表面/错误等槽位沿用基线，
 * 保持中性背景与统一的破坏性语义。
 */
package com.example.autop.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/** HSL 表示（色相 0..360，饱和度与明度 0..1）。 */
public data class HslColor(
    public val hue: Float,
    public val saturation: Float,
    public val lightness: Float,
)

/** 任意颜色转 HSL（取色条初始值）。 */
@Suppress("MagicNumber") // HSL 标准公式的固定系数（0.5 / 2 / 6 / 60）
public fun Color.toHsl(): HslColor {
    val maxChannel = max(red, max(green, blue))
    val minChannel = min(red, min(green, blue))
    val lightness = (maxChannel + minChannel) / 2f
    if (maxChannel == minChannel) return HslColor(hue = 0f, saturation = 0f, lightness = lightness)
    val delta = maxChannel - minChannel
    val saturation =
        if (lightness > 0.5f) delta / (2f - maxChannel - minChannel) else delta / (maxChannel + minChannel)
    val hue =
        when (maxChannel) {
            red -> (green - blue) / delta + if (green < blue) 6f else 0f
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        } * 60f
    return HslColor(hue = hue, saturation = saturation, lightness = lightness)
}

/** 取色条交给配置的稳定 ARGB 值（0xAARRGGBB）。 */
public fun Color.toSeedArgb(): Long = toArgb().toLong() and ARGB_MASK

/** 从单个种子色推导 M3 色板。 */
public object SeedPalette {
    /** 浅色方案。 */
    public fun light(seedArgb: Long): ColorScheme = scheme(seedArgb, dark = false)

    /** 深色方案。 */
    public fun dark(seedArgb: Long): ColorScheme = scheme(seedArgb, dark = true)

    @Suppress("MagicNumber") // 色调梯度（饱和/明度系数）是色板设计参数，集中在此便于整体校对
    private fun scheme(
        seedArgb: Long,
        dark: Boolean,
    ): ColorScheme {
        val seed = Color(seedArgb).toHsl()
        val tone = seed.lightness.coerceIn(0f, 1f)
        val saturation = seed.saturation.coerceIn(0f, 1f)
        val tertiaryHue = (seed.hue + TERTIARY_HUE_SHIFT) % HUE_CYCLE
        if (dark) {
            val primary =
                hsl(seed.hue, min(saturation, 0.5f), lerp(0.70f, 0.86f, tone))
            // 深色容器压到 L≤0.32：高明度色相（黄/绿）的容器过亮会让浅色 on 色对比不足。
            val container =
                hsl(seed.hue, min(saturation * 0.7f, 0.45f), lerp(0.21f, 0.28f, tone))
            val secondary =
                hsl(seed.hue, min(saturation * 0.28f, 0.22f), 0.80f)
            val secondaryContainer =
                hsl(seed.hue, min(saturation * 0.2f, 0.16f), lerp(0.24f, 0.31f, tone))
            val tertiary = hsl(tertiaryHue, min(saturation * 0.3f, 0.22f), 0.82f)
            val tertiaryContainer =
                hsl(tertiaryHue, min(saturation * 0.2f, 0.16f), lerp(0.26f, 0.32f, tone))
            return AutopDarkColors.copy(
                primary = primary,
                onPrimary = onColorFor(primary),
                primaryContainer = container,
                onPrimaryContainer = hsl(seed.hue, saturation * 0.6f, 0.92f),
                inversePrimary = hsl(seed.hue, min(saturation, 0.55f), 0.42f),
                secondary = secondary,
                onSecondary = onColorFor(secondary),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = hsl(seed.hue, saturation * 0.35f, 0.92f),
                tertiary = tertiary,
                onTertiary = onColorFor(tertiary),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = hsl(tertiaryHue, saturation * 0.35f, 0.92f),
            )
        }
        val primary = hsl(seed.hue, min(saturation, 0.65f), lerp(0.30f, 0.52f, tone))
        val container = hsl(seed.hue, min(saturation * 0.6f, 0.45f), lerp(0.84f, 0.94f, tone))
        val secondary = hsl(seed.hue, min(saturation * 0.32f, 0.25f), 0.40f)
        val secondaryContainer = hsl(seed.hue, min(saturation * 0.2f, 0.16f), lerp(0.87f, 0.94f, tone))
        val tertiary = hsl(tertiaryHue, min(saturation * 0.34f, 0.25f), 0.40f)
        val tertiaryContainer = hsl(tertiaryHue, min(saturation * 0.22f, 0.18f), lerp(0.87f, 0.94f, tone))
        return AutopLightColors.copy(
            primary = primary,
            onPrimary = onColorFor(primary),
            primaryContainer = container,
            onPrimaryContainer = hsl(seed.hue, saturation * 0.7f, 0.18f),
            inversePrimary = hsl(seed.hue, min(saturation, 0.55f), 0.80f),
            secondary = secondary,
            onSecondary = onColorFor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = hsl(seed.hue, saturation * 0.4f, 0.18f),
            tertiary = tertiary,
            onTertiary = onColorFor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = hsl(tertiaryHue, saturation * 0.4f, 0.18f),
        )
    }
}

/** 前景色取黑/白中对比度更高的一方（任意色相下都保证可读）。 */
private fun onColorFor(color: Color): Color =
    if (contrastRatio(color, Color.White) >= contrastRatio(color, Color.Black)) Color.White else Color.Black

private fun contrastRatio(
    first: Color,
    second: Color,
): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (max(firstLuminance, secondLuminance) + CONTRAST_OFFSET) /
        (min(firstLuminance, secondLuminance) + CONTRAST_OFFSET)
}

private fun hsl(
    hue: Float,
    saturation: Float,
    lightness: Float,
): Color = Color.hsl(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))

private fun lerp(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction

private const val ARGB_MASK: Long = 0xFFFFFFFFL
private const val HUE_CYCLE: Float = 360f
private const val TERTIARY_HUE_SHIFT: Float = 60f
private const val CONTRAST_OFFSET: Float = 0.05f
