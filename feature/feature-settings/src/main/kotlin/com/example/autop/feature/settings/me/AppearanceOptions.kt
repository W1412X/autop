/**
 * 外观档位选项（docs/tech/08 §1.2 字号 0.9 / 1.0 / 1.15 / 1.3；§1.3 图标 0.9 / 1.0 / 1.15）。
 *
 * 深色模式与密度取 core-ui 枚举名（与 `ui.appearance` 的 `@SerialName` 值一致），
 * 主题色索引范围取 `SeedColors`，档位值以本表为唯一来源。
 */
package com.example.autop.feature.settings.me

import com.example.autop.core.ui.theme.DarkMode
import com.example.autop.core.ui.theme.SeedColors
import com.example.autop.core.ui.theme.UiDensity

internal object AppearanceOptions {
    val DARK_MODES: List<String> = DarkMode.entries.map { it.name }
    val DENSITIES: List<String> = UiDensity.entries.map { it.name }
    val SEED_INDEXES: List<Int> = (0 until SeedColors.COUNT).toList()
    val FONT_SCALES: List<Double> = listOf(0.9, 1.0, 1.15, 1.3)
    val ICON_SCALES: List<Double> = listOf(0.9, 1.0, 1.15)
}
