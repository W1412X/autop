/**
 * "我的"页外观分组（docs/tech/08 §4.5）：五个选择行 + 两个开关行，选择弹层共用 [SheetKind]。
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

/** 外观分组（docs/tech/08 §4.5）：五个选择行 + 两个开关行。 */
@Composable
internal fun AppearanceSection(
    appearance: UiAppearanceSection,
    onOpenSheet: (SheetKind) -> Unit,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_section_appearance))
        OptionRow(
            title = stringResource(R.string.me_appearance_dark_mode),
            subtitle = stringResource(R.string.me_sub_dark_mode),
            value = SheetKind.DARK_MODE.labelOf(appearance.darkMode),
            onClick = { onOpenSheet(SheetKind.DARK_MODE) },
        )
        SwitchRow(
            title = stringResource(R.string.me_appearance_dynamic_color),
            subtitle = stringResource(R.string.me_sub_dynamic_color),
            checked = appearance.dynamicColor,
            onCheckedChange = { onAction(MeAction.SetDynamicColor(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_seed),
            subtitle = stringResource(R.string.me_sub_seed),
            value = SheetKind.SEED.labelOf(appearance.seedIndex.toString()),
            onClick = { onOpenSheet(SheetKind.SEED) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_font_scale),
            subtitle = stringResource(R.string.me_sub_font_scale),
            value = SheetKind.FONT_SCALE.labelOf(appearance.fontScale.toString()),
            onClick = { onOpenSheet(SheetKind.FONT_SCALE) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_icon_scale),
            subtitle = stringResource(R.string.me_sub_icon_scale),
            value = SheetKind.ICON_SCALE.labelOf(appearance.iconScale.toString()),
            onClick = { onOpenSheet(SheetKind.ICON_SCALE) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_density),
            subtitle = stringResource(R.string.me_sub_density),
            value = SheetKind.DENSITY.labelOf(appearance.density),
            onClick = { onOpenSheet(SheetKind.DENSITY) },
        )
        SwitchRow(
            title = stringResource(R.string.me_appearance_motion),
            subtitle = stringResource(R.string.me_sub_motion),
            checked = appearance.motionEnabled,
            onCheckedChange = { onAction(MeAction.SetMotionEnabled(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_appearance_language),
            subtitle = null,
            value = SheetKind.LANGUAGE.labelOf(appearance.language),
            onClick = { onOpenSheet(SheetKind.LANGUAGE) },
        )
    }
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
