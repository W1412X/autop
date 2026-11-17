/**
 * 外观分组的 OptionSheet 类型与选项文案（docs/tech/08 §4.5 外观分组的五个选择行）。
 */
package com.example.autop.feature.settings.me

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.config.UiAppearanceSection
import com.example.autop.core.ui.component.OptionIcon
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.ballface.BallFaceColor
import com.example.autop.core.ui.component.ballface.BallFaceVariant
import com.example.autop.core.ui.theme.DarkMode
import com.example.autop.core.ui.theme.SeedColors
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
    BALL_SHAPE(R.string.me_appearance_ball_shape),
    BALL_COLOR(R.string.me_appearance_ball_color),
    PET_FACE_MIN_HOLD(R.string.me_appearance_pet_hold),
    PET_REROLL(R.string.me_appearance_pet_reroll),
    ;

    /** 当前选择的选项 id（与写入配置的原值一致）。 */
    fun selectedId(appearance: UiAppearanceSection): String =
        when (this) {
            DARK_MODE -> appearance.darkMode
            SEED ->
                if (appearance.customSeed != 0L) {
                    AppearanceOptions.CUSTOM_SEED_ID
                } else {
                    // 旧版本档位可能超出当前预设数量，夹取到有效档位再显示。
                    appearance.seedIndex.coerceIn(0, SeedColors.COUNT - 1).toString()
                }
            FONT_SCALE -> appearance.fontScale.toString()
            ICON_SCALE -> appearance.iconScale.toString()
            DENSITY -> appearance.density
            LANGUAGE -> appearance.language
            BALL_SHAPE -> appearance.ballShape
            BALL_COLOR -> appearance.ballColor
            PET_FACE_MIN_HOLD -> appearance.petFaceMinHoldMs.toString()
            PET_REROLL -> appearance.petRerollMs.toString()
        }

    /** 选项 id → 页面操作。 */
    fun toAction(id: String): MeAction =
        when (this) {
            DARK_MODE -> MeAction.SetDarkMode(id)
            // 自定义项由 MeSheetHost 拦截（打开调色盘），不会走到这里。
            SEED -> MeAction.SetSeedIndex(id.toIntOrNull() ?: 0)
            FONT_SCALE -> MeAction.SetFontScale(id.toDouble())
            ICON_SCALE -> MeAction.SetIconScale(id.toDouble())
            DENSITY -> MeAction.SetDensity(id)
            LANGUAGE -> MeAction.SetLanguage(id)
            BALL_SHAPE -> MeAction.SetBallShape(id)
            BALL_COLOR -> MeAction.SetBallColor(id)
            PET_FACE_MIN_HOLD -> MeAction.SetPetFaceMinHoldMs(id.toLong())
            PET_REROLL -> MeAction.SetPetRerollMs(id.toLong())
        }
}

/** 该弹层的选项列表（id 为写入配置的原值字符串）。 */
@Composable
internal fun SheetKind.options(): List<OptionItem> =
    when (this) {
        SheetKind.DARK_MODE ->
            AppearanceOptions.DARK_MODES.map { OptionItem(id = it, label = darkModeLabel(it)) }
        SheetKind.SEED -> seedOptions()
        SheetKind.FONT_SCALE ->
            AppearanceOptions.FONT_SCALES.map { OptionItem(id = it.toString(), label = scaleLabel(it)) }
        SheetKind.ICON_SCALE ->
            AppearanceOptions.ICON_SCALES.map { OptionItem(id = it.toString(), label = scaleLabel(it)) }
        SheetKind.DENSITY ->
            AppearanceOptions.DENSITIES.map { OptionItem(id = it, label = densityLabel(it)) }
        SheetKind.LANGUAGE ->
            AppearanceOptions.LANGUAGES.map { OptionItem(id = it, label = languageLabel(it)) }
        SheetKind.BALL_SHAPE ->
            AppearanceOptions.BALL_SHAPES.map { OptionItem(id = it, label = ballShapeLabel(it)) }
        SheetKind.BALL_COLOR ->
            AppearanceOptions.BALL_COLORS.map { OptionItem(id = it, label = ballColorLabel(it)) }
        SheetKind.PET_FACE_MIN_HOLD ->
            AppearanceOptions.PET_HOLD_OPTIONS_MS.map { ms ->
                OptionItem(id = ms.toString(), label = if (ms == 0L) holdOffLabel() else secondsLabel(ms))
            }
        SheetKind.PET_REROLL ->
            AppearanceOptions.PET_REROLL_OPTIONS_MS.map { OptionItem(id = it.toString(), label = secondsLabel(it)) }
    }

/** 当前选择 id 的显示文案。 */
@Composable
internal fun SheetKind.labelOf(id: String): String = options().firstOrNull { it.id == id }?.label ?: id

/** 主题色设置行（及分组副标题）的当前值：自定义颜色优先于预设名。 */
@Composable
internal fun seedValueLabel(appearance: UiAppearanceSection): String =
    if (appearance.customSeed != 0L) {
        stringResource(R.string.me_seed_custom)
    } else {
        val seedIndex = appearance.seedIndex.coerceIn(0, SeedColors.COUNT - 1)
        SheetKind.SEED.labelOf(seedIndex.toString())
    }

/** 主题色选项：预设（带色点）+ 末尾“自定义”入口。 */
@Composable
private fun seedOptions(): List<OptionItem> =
    AppearanceOptions.SEED_INDEXES.map { index ->
        OptionItem(
            id = index.toString(),
            label = seedLabel(index),
            swatch = SeedColors.light(index).primary,
        )
    } +
        OptionItem(
            id = AppearanceOptions.CUSTOM_SEED_ID,
            label = stringResource(R.string.me_seed_custom),
            icon = OptionIcon.PALETTE,
        )

/** 主题色档位文案（docs/tech/08 §1.1 预设主题色顺序，须与 `SeedColors` 索引一致）。 */
private val seedLabelRes: List<Int> =
    listOf(
        R.string.me_seed_lime,
        R.string.me_seed_lake,
        R.string.me_seed_mint,
        R.string.me_seed_cocoa,
        R.string.me_seed_violet,
        R.string.me_seed_rose,
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
private fun ballShapeLabel(value: String): String =
    when (value) {
        BallFaceVariant.LINE.name -> stringResource(R.string.me_ball_shape_line)
        else -> stringResource(R.string.me_ball_shape_solid)
    }

@Composable
private fun ballColorLabel(value: String): String =
    when (value) {
        BallFaceColor.BLACK.name -> stringResource(R.string.me_ball_color_black)
        BallFaceColor.WHITE.name -> stringResource(R.string.me_ball_color_white)
        else -> stringResource(R.string.me_ball_color_theme)
    }

@Composable
private fun holdOffLabel(): String = stringResource(R.string.me_pet_hold_off)

@Composable
private fun secondsLabel(ms: Long): String = stringResource(R.string.me_pet_seconds, ms.toFloat() / MILLIS_PER_SECOND)

@Composable
private fun languageLabel(value: String): String =
    when (value) {
        AppearanceOptions.LANG_EN -> stringResource(R.string.me_language_en)
        AppearanceOptions.LANG_ZH -> stringResource(R.string.me_language_zh)
        else -> stringResource(R.string.me_language_system)
    }
