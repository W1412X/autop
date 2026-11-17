/**
 * 外观档位选项（docs/tech/08 §1.2 字号 0.9 / 1.0 / 1.15 / 1.3；§1.3 图标 0.9 / 1.0 / 1.15）。
 *
 * 深色模式与密度取 core-ui 枚举名（与 `ui.appearance` 的 `@SerialName` 值一致），
 * 主题色索引范围取 `SeedColors`，档位值以本表为唯一来源。
 */
package com.example.autop.feature.settings.me

import com.example.autop.core.ui.component.ballface.BallFaceColor
import com.example.autop.core.ui.component.ballface.BallFaceVariant
import com.example.autop.core.ui.theme.DarkMode
import com.example.autop.core.ui.theme.SeedColors
import com.example.autop.core.ui.theme.UiDensity

internal object AppearanceOptions {
    val DARK_MODES: List<String> = DarkMode.entries.map { it.name }
    val DENSITIES: List<String> = UiDensity.entries.map { it.name }
    val BALL_SHAPES: List<String> = BallFaceVariant.entries.map { it.name }
    val BALL_COLORS: List<String> = BallFaceColor.entries.map { it.name }

    /** 表情驻留档位（毫秒，0 = 不驻留；默认 2500 与 `ui.appearance.petFaceMinHoldMs` 一致）。 */
    val PET_HOLD_OPTIONS_MS: List<Long> = listOf(0L, 1_000L, 1_500L, 2_000L, 2_500L, 3_000L, 4_000L)

    /** 换脸间隔档位（毫秒；默认 2500 与 `ui.appearance.petRerollMs` 一致）。 */
    val PET_REROLL_OPTIONS_MS: List<Long> = listOf(1_500L, 2_000L, 2_500L, 3_000L, 4_000L)
    val SEED_INDEXES: List<Int> = (0 until SeedColors.COUNT).toList()
    val FONT_SCALES: List<Double> = listOf(0.9, 1.0, 1.15, 1.3)
    val ICON_SCALES: List<Double> = listOf(0.9, 1.0, 1.15)

    /** 主题色列表里的“自定义”项 id（选中后打开调色盘，不直接写配置）。 */
    const val CUSTOM_SEED_ID: String = "custom"

    /** 输出语言档位（`ui.appearance.language` 原值）。 */
    const val LANG_SYSTEM: String = "system"
    const val LANG_ZH: String = "zh"
    const val LANG_EN: String = "en"
    val LANGUAGES: List<String> = listOf(LANG_SYSTEM, LANG_ZH, LANG_EN)
}
