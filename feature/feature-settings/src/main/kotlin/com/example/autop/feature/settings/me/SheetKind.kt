/**
 * 外观分组的 OptionSheet 类型与选项文案（docs/tech/08 §4.5 外观分组的五个选择行）。
 */
package com.example.autop.feature.settings.me

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.config.UiAppearanceSection
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.theme.DarkMode
import com.example.autop.core.ui.theme.UiDensity
import com.example.autop.feature.settings.R
import kotlin.math.roundToInt

internal enum class SheetKind(
    @get:StringRes val titleRes: Int,
) {
    DARK_MODE(R.string.me_appearance_dark_mode),
    SEED(R.string.me_appearance_seed),
    FONT_SCALE(R.string.me_appearance_font_scale),
    ICON_SCALE(R.string.me_appearance_icon_scale),
    DENSITY(R.string.me_appearance_density),
    LANGUAGE(R.string.me_appearance_language),
    ;

    /** 当前选择的选项 id（与写入配置的原值一致）。 */
    fun selectedId(appearance: UiAppearanceSection): String =
        when (this) {
            DARK_MODE -> appearance.darkMode
            SEED -> appearance.seedIndex.toString()
            FONT_SCALE -> appearance.fontScale.toString()
            ICON_SCALE -> appearance.iconScale.toString()
            DENSITY -> appearance.density
            LANGUAGE -> appearance.language
        }

    /** 选项 id → 页面操作。 */
    fun toAction(id: String): MeAction =
        when (this) {
            DARK_MODE -> MeAction.SetDarkMode(id)
            SEED -> MeAction.SetSeedIndex(id.toInt())
            FONT_SCALE -> MeAction.SetFontScale(id.toDouble())
            ICON_SCALE -> MeAction.SetIconScale(id.toDouble())
            DENSITY -> MeAction.SetDensity(id)
            LANGUAGE -> MeAction.SetLanguage(id)
        }
}

/** 该弹层的选项列表（id 为写入配置的原值字符串）。 */
@Composable
internal fun SheetKind.options(): List<OptionItem> =
    when (this) {
        SheetKind.DARK_MODE ->
            AppearanceOptions.DARK_MODES.map { OptionItem(id = it, label = darkModeLabel(it)) }
        SheetKind.SEED ->
            AppearanceOptions.SEED_INDEXES.map { OptionItem(id = it.toString(), label = seedLabel(it)) }
        SheetKind.FONT_SCALE ->
            AppearanceOptions.FONT_SCALES.map { OptionItem(id = it.toString(), label = scaleLabel(it)) }
        SheetKind.ICON_SCALE ->
            AppearanceOptions.ICON_SCALES.map { OptionItem(id = it.toString(), label = scaleLabel(it)) }
        SheetKind.DENSITY ->
            AppearanceOptions.DENSITIES.map { OptionItem(id = it, label = densityLabel(it)) }
        SheetKind.LANGUAGE ->
            AppearanceOptions.LANGUAGES.map { OptionItem(id = it, label = languageLabel(it)) }
    }

/** 当前选择 id 的显示文案。 */
@Composable
internal fun SheetKind.labelOf(id: String): String = options().firstOrNull { it.id == id }?.label ?: id

/** 主题色档位文案（docs/tech/08 §1.1 预设主题色顺序）。 */
private val seedLabelRes: List<Int> =
    listOf(
        R.string.me_seed_lime,
        R.string.me_seed_lake,
        R.string.me_seed_orange,
        R.string.me_seed_violet,
        R.string.me_seed_graphite,
    )

/** 百分比换算（docs/tech/08 §1.2 档位以倍率存储、按百分比显示）。 */
private const val PERCENT_FACTOR: Int = 100

@Composable
private fun darkModeLabel(value: String): String =
    when (value) {
        DarkMode.LIGHT.name -> stringResource(R.string.me_dark_mode_light)
        DarkMode.DARK.name -> stringResource(R.string.me_dark_mode_dark)
        else -> stringResource(R.string.me_dark_mode_system)
    }

@Composable
private fun seedLabel(index: Int): String = stringResource(seedLabelRes.getOrElse(index) { seedLabelRes.first() })

@Composable
private fun scaleLabel(value: Double): String =
    stringResource(R.string.me_scale_percent, (value * PERCENT_FACTOR).roundToInt())

@Composable
private fun densityLabel(value: String): String =
    when (value) {
        UiDensity.COMPACT.name -> stringResource(R.string.me_density_compact)
        UiDensity.COMFORTABLE.name -> stringResource(R.string.me_density_comfortable)
        else -> stringResource(R.string.me_density_standard)
    }

@Composable
private fun languageLabel(value: String): String =
    when (value) {
        AppearanceOptions.LANG_EN -> stringResource(R.string.me_language_en)
        AppearanceOptions.LANG_ZH -> stringResource(R.string.me_language_zh)
        else -> stringResource(R.string.me_language_system)
    }
