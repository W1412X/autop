/**
 * 外观分组内容（二级页顶栏已显示分组名）：通用外观的选择行与开关，末尾是“桌宠”小组。
 * 选择弹层共用 [SheetKind]。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.config.UiAppearanceSection
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.feature.settings.R

/** 外观分组内容（二级页顶栏已显示分组名）：五个选择行 + 两个开关行，选择弹层共用 [SheetKind]。 */
@Composable
internal fun AppearanceSection(
    appearance: UiAppearanceSection,
    onOpenSheet: (SheetKind) -> Unit,
    onAction: (MeAction) -> Unit,
) {
    Column {
        OptionRow(
            title = stringResource(R.string.me_appearance_dark_mode),
            subtitle = null,
            value = SheetKind.DARK_MODE.labelOf(appearance.darkMode),
            onClick = { onOpenSheet(SheetKind.DARK_MODE) },
        )
        SwitchRow(
            title = stringResource(R.string.me_appearance_dynamic_color),
            subtitle = null,
            checked = appearance.dynamicColor,
            onCheckedChange = { onAction(MeAction.SetDynamicColor(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_seed),
            subtitle = null,
            value = seedValueLabel(appearance),
            onClick = { onOpenSheet(SheetKind.SEED) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_font_scale),
            subtitle = null,
            value = SheetKind.FONT_SCALE.labelOf(appearance.fontScale.toString()),
            onClick = { onOpenSheet(SheetKind.FONT_SCALE) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_icon_scale),
            subtitle = null,
            value = SheetKind.ICON_SCALE.labelOf(appearance.iconScale.toString()),
            onClick = { onOpenSheet(SheetKind.ICON_SCALE) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_density),
            subtitle = null,
            value = SheetKind.DENSITY.labelOf(appearance.density),
            onClick = { onOpenSheet(SheetKind.DENSITY) },
        )
        SwitchRow(
            title = stringResource(R.string.me_appearance_motion),
            subtitle = null,
            checked = appearance.motionEnabled,
            onCheckedChange = { onAction(MeAction.SetMotionEnabled(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_language),
            subtitle = null,
            value = SheetKind.LANGUAGE.labelOf(appearance.language),
            onClick = { onOpenSheet(SheetKind.LANGUAGE) },
        )
        PetGroup(appearance = appearance, onOpenSheet = onOpenSheet, onAction = onAction)
    }
}

/**
 * “桌宠”小组：所有小脸图标（悬浮球、卡片、控制条、列表）统一读取这些配置（ADR-0045）。
 * 形态、颜色、驻留、换脸间隔是选择行，运行中按钮是开关。
 */
@Composable
private fun PetGroup(
    appearance: UiAppearanceSection,
    onOpenSheet: (SheetKind) -> Unit,
    onAction: (MeAction) -> Unit,
) {
    SectionTitle(text = stringResource(R.string.me_appearance_pet_group))
    OptionRow(
        title = stringResource(R.string.me_appearance_ball_shape),
        subtitle = null,
        value = SheetKind.BALL_SHAPE.labelOf(appearance.ballShape),
        onClick = { onOpenSheet(SheetKind.BALL_SHAPE) },
    )
    OptionRow(
        title = stringResource(R.string.me_appearance_ball_color),
        subtitle = null,
        value = SheetKind.BALL_COLOR.labelOf(appearance.ballColor),
        onClick = { onOpenSheet(SheetKind.BALL_COLOR) },
    )
    OptionRow(
        title = stringResource(R.string.me_appearance_pet_hold),
        subtitle = null,
        value = SheetKind.PET_FACE_MIN_HOLD.labelOf(appearance.petFaceMinHoldMs.toString()),
        onClick = { onOpenSheet(SheetKind.PET_FACE_MIN_HOLD) },
    )
    OptionRow(
        title = stringResource(R.string.me_appearance_pet_reroll),
        subtitle = null,
        value = SheetKind.PET_REROLL.labelOf(appearance.petRerollMs.toString()),
        onClick = { onOpenSheet(SheetKind.PET_REROLL) },
    )
    SwitchRow(
        title = stringResource(R.string.me_appearance_pet_menu_actions),
        subtitle = null,
        checked = appearance.petMenuActionsWhileRunning,
        onCheckedChange = { onAction(MeAction.SetPetMenuActionsWhileRunning(it)) },
    )
}

/** 外观选择弹层宿主：有选中类型时显示对应弹层（docs/tech/08 §4.5）。 */
@Composable
internal fun AppearanceSheetHost(
    kind: SheetKind?,
    appearance: UiAppearanceSection,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (kind == null) return
    AppearanceSheet(kind = kind, appearance = appearance, onSelect = onSelect, onDismiss = onDismiss)
}

/** 外观选择弹层。 */
@Composable
private fun AppearanceSheet(
    kind: SheetKind,
    appearance: UiAppearanceSection,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(kind.titleRes),
        options = kind.options(),
        selectedId = kind.selectedId(appearance),
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
