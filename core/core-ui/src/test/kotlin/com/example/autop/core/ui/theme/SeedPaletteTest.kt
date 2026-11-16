/**
 * [SeedPalette] 单测（docs/tech/08 §1.1）：中性表面沿用基线、on 色对比度达标、
 * 色相与种子一致、同样入参得到同样色板。
 */
package com.example.autop.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SeedPaletteTest {
    @Test
    fun `neutral surfaces stay on baseline`() {
        for (seed in SEEDS) {
            assertNeutrals(SeedPalette.light(seed), AutopLightColors)
            assertNeutrals(SeedPalette.dark(seed), AutopDarkColors)
        }
    }

    @Test
    fun `on colors reach readable contrast for any hue`() {
        for (hue in HUES) {
            for (lightness in LIGHTNESSES) {
                val seed = Color.hsl(hue, SATURATION, lightness).toSeedArgb()
                assertContrast(SeedPalette.light(seed))
                assertContrast(SeedPalette.dark(seed))
            }
        }
    }

    @Test
    fun `primary keeps seed hue`() {
        val seed = Color.hsl(TARGET_HUE, SATURATION, 0.5f).toSeedArgb()
        val primary = SeedPalette.light(seed).primary
        val hue = primary.toHsl().hue
        val delta = abs(shortestHueDelta(hue, TARGET_HUE))
        assertThat(delta <= HUE_TOLERANCE).isTrue()
    }

    @Test
    fun `seed argb round trips and hsl keeps color`() {
        for (seed in SEEDS) {
            val original = Color(seed)
            assertThat(original.toSeedArgb()).isEqualTo(seed)
            val hsl = original.toHsl()
            val restored = Color.hsl(hsl.hue, hsl.saturation, hsl.lightness)
            assertThat(abs(restored.red - original.red).toDouble()).isAtMost(CHANNEL_TOLERANCE)
            assertThat(abs(restored.green - original.green).toDouble()).isAtMost(CHANNEL_TOLERANCE)
            assertThat(abs(restored.blue - original.blue).toDouble()).isAtMost(CHANNEL_TOLERANCE)
        }
    }

    @Test
    fun `same seed yields same scheme`() {
        // ColorScheme 未实现 equals，逐槽位比对（推导是纯函数）。
        val first = SeedPalette.light(SEEDS.first())
        val second = SeedPalette.light(SEEDS.first())
        assertThat(first.primary).isEqualTo(second.primary)
        assertThat(first.primaryContainer).isEqualTo(second.primaryContainer)
        assertThat(first.onPrimary).isEqualTo(second.onPrimary)
        assertThat(SeedPalette.dark(SEEDS.first()).primary)
            .isEqualTo(SeedPalette.dark(SEEDS.first()).primary)
    }

    private fun assertNeutrals(
        scheme: ColorScheme,
        baseline: ColorScheme,
    ) {
        assertThat(scheme.background).isEqualTo(baseline.background)
        assertThat(scheme.onBackground).isEqualTo(baseline.onBackground)
        assertThat(scheme.surface).isEqualTo(baseline.surface)
        assertThat(scheme.onSurface).isEqualTo(baseline.onSurface)
        assertThat(scheme.surfaceVariant).isEqualTo(baseline.surfaceVariant)
        assertThat(scheme.onSurfaceVariant).isEqualTo(baseline.onSurfaceVariant)
        assertThat(scheme.outline).isEqualTo(baseline.outline)
        assertThat(scheme.error).isEqualTo(baseline.error)
        assertThat(scheme.onError).isEqualTo(baseline.onError)
    }

    private fun assertContrast(scheme: ColorScheme) {
        val sets =
            listOf(
                scheme.primary to scheme.onPrimary,
                scheme.primaryContainer to scheme.onPrimaryContainer,
                scheme.secondary to scheme.onSecondary,
                scheme.secondaryContainer to scheme.onSecondaryContainer,
                scheme.tertiary to scheme.onTertiary,
                scheme.tertiaryContainer to scheme.onTertiaryContainer,
            )
        for ((background, foreground) in sets) {
            assertThat(contrast(background, foreground)).isAtLeast(MIN_CONTRAST)
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Double {
        val firstLuminance = first.luminance().toDouble()
        val secondLuminance = second.luminance().toDouble()
        return (max(firstLuminance, secondLuminance) + CONTRAST_OFFSET) /
            (min(firstLuminance, secondLuminance) + CONTRAST_OFFSET)
    }

    private fun shortestHueDelta(
        first: Float,
        second: Float,
    ): Float {
        val delta = (first - second) % HUE_CYCLE
        return when {
            delta > HUE_CYCLE / 2f -> delta - HUE_CYCLE
            delta < -HUE_CYCLE / 2f -> delta + HUE_CYCLE
            else -> delta
        }
    }

    private companion object {
        const val SATURATION: Float = 0.8f
        const val TARGET_HUE: Float = 210f
        const val HUE_TOLERANCE: Float = 3f
        const val HUE_CYCLE: Float = 360f
        const val CONTRAST_OFFSET: Double = 0.05
        const val MIN_CONTRAST: Double = 4.5

        /** HSL → RGB、再反推 HSL 的浮点误差（< 1/255 量级）。 */
        const val CHANNEL_TOLERANCE: Double = 0.01

        val HUES: List<Float> = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
        val LIGHTNESSES: List<Float> = listOf(0.08f, 0.5f, 0.92f)

        /** 覆盖冷暖与低饱和：种子只用于提供色相，取值本身不影响中性表面。 */
        val SEEDS: List<Long> =
            listOf(0xFF8E4A5EL, 0xFF2E6A5FL, 0xFF7A5842L, 0xFF4A5A94L, 0xFF9E9E9EL)
    }
}
