/**
 * "我的"二级页“权限与运行”（docs/tech/08 §4.5）：三个小分组。
 * - 权限：权限检查、注入通道（可选）、常驻后台（从一级状态卡跳入时定位高亮）；
 * - 悬浮球：悬浮窗通道、显示与显示方式、透明度（`ui.overlay.*`，docs/tech/05 §10）；
 * - 执行：执行期屏蔽触摸（N215）、动作后等待、运行预算、结束后返回原应用、导航应用。
 *
 * 选择类设置的弹层开关由 [MeSheetState] 持有，弹层本身由 [MeSheetHost] 渲染。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.device.ShellState
import com.example.autop.feature.settings.MeFocus
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.SettingsSection

/** 权限与运行二级页内容：权限、悬浮球、执行三个小分组。 */
@Composable
internal fun PermissionsSection(
    state: MeUiState,
    focus: MeFocus?,
    onOpen: (SettingsSection) -> Unit,
    sheets: MeSheetState,
    onAction: (MeAction) -> Unit,
) {
    PermissionSubsection(state = state, focus = focus, onOpen = onOpen, onAction = onAction)
    OverlaySubsection(state = state, sheets = sheets, onAction = onAction)
    ExecutionSubsection(state = state, onOpen = onOpen, sheets = sheets, onAction = onAction)
}

/** 权限小分组：权限检查、注入通道（可选）、常驻后台（可从一级状态卡定位高亮）。 */
@Composable
private fun PermissionSubsection(
    state: MeUiState,
    focus: MeFocus?,
    onOpen: (SettingsSection) -> Unit,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_sub_section_permission))
        NavRow(
            title = stringResource(R.string.me_nav_permission_check),
            subtitle = null,
            onClick = { onOpen(SettingsSection.PERMISSIONS) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_shell),
            subtitle = stringResource(shellChannelStatusRes(state.shellChannel)),
            onClick = { onOpen(SettingsSection.SHELL) },
        )
        FocusHighlight(focused = focus == MeFocus.HOST_PERSISTENT) {
            SwitchRow(
                title = stringResource(R.string.me_host_persistent),
                subtitle = stringResource(R.string.me_host_persistent_desc),
                checked = state.hostPersistent,
                onCheckedChange = { onAction(MeAction.SetHostPersistent(it)) },
            )
        }
    }
}

/** 悬浮球小分组：显隐、悬浮窗通道、显示方式、透明度（`ui.overlay.*`）。 */
@Composable
private fun OverlaySubsection(
    state: MeUiState,
    sheets: MeSheetState,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_sub_section_overlay))
        SwitchRow(
            title = stringResource(R.string.me_overlay_enabled),
            subtitle = null,
            checked = state.overlayEnabled,
            onCheckedChange = { onAction(MeAction.SetOverlayEnabled(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_overlay_channel),
            subtitle = stringResource(R.string.me_sub_overlay_channel),
            value = WindowTypeLabel(state.overlayWindowType),
            onClick = { sheets.windowType = true },
        )
        OptionRow(
            title = stringResource(R.string.me_overlay_display_mode),
            subtitle = stringResource(R.string.me_sub_display_mode),
            value = DisplayModeLabel(state.overlayDisplayMode),
            onClick = { sheets.displayMode = true },
        )
        OptionRow(
            title = stringResource(R.string.me_overlay_opacity),
            subtitle = null,
            value = opacityLabel(state.overlayOpacity),
            onClick = { sheets.opacity = true },
        )
    }
}

/** 执行小分组：触摸盾、动作后等待、运行预算、结束后返回原应用、导航应用。 */
@Composable
private fun ExecutionSubsection(
    state: MeUiState,
    onOpen: (SettingsSection) -> Unit,
    sheets: MeSheetState,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_sub_section_execution))
        SwitchRow(
            title = stringResource(R.string.me_shield_enabled),
            subtitle = stringResource(R.string.me_shield_enabled_desc),
            checked = state.shieldEnabled,
            onCheckedChange = { onAction(MeAction.SetShieldEnabled(it)) },
        )
        OptionRow(
            title = stringResource(R.string.settings_action_delay_title),
            subtitle = stringResource(R.string.settings_action_delay_subtitle),
            value =
                stringResource(
                    R.string.settings_action_delay_seconds,
                    (state.actionDelayMs / MILLIS_PER_SECOND).toInt(),
                ),
            onClick = { sheets.actionDelay = true },
        )
        NavRow(
            title = stringResource(R.string.me_nav_budget),
            subtitle = null,
            onClick = { onOpen(SettingsSection.BUDGET) },
        )
        SwitchRow(
            title = stringResource(R.string.me_return_to_origin),
            subtitle = null,
            checked = state.returnToOriginApp,
            onCheckedChange = { onAction(MeAction.SetReturnToOriginApp(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_nav_navigation_app),
            subtitle = stringResource(R.string.me_sub_navigation_app),
            value = navigationLabel(state.navigationPreferred, state.navigationApps),
            onClick = { sheets.navigation = true },
        )
    }
}

/** 悬浮窗通道选择弹层。 */
@Composable
internal fun WindowTypeSheet(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.me_overlay_channel),
        options = WindowTypeOptions(),
        selectedId = selectedId,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** 悬浮球透明度选择弹层（球体与贴边小条统一生效）。 */
@Composable
internal fun OpacitySheet(
    selected: Double,
    onSelect: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val options =
        OPACITY_OPTIONS.map { value ->
            OptionItem(id = value.toString(), label = opacityLabel(value))
        }
    OptionSheet(
        title = stringResource(R.string.me_overlay_opacity),
        options = options,
        selectedId = OPACITY_OPTIONS.firstOrNull { it == selected }?.toString().orEmpty(),
        onSelect = { id -> id.toDoubleOrNull()?.let(onSelect) },
        onDismiss = onDismiss,
    )
}

/** 透明度显示文案（百分比）。 */
@Composable
private fun opacityLabel(value: Double): String = stringResource(R.string.me_scale_percent, (value * PERCENT).toInt())

/** 悬浮球显示方式选择弹层。 */
@Composable
internal fun DisplayModeSheet(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.me_overlay_display_mode),
        options = DisplayModeOptions(),
        selectedId = selectedId,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** 悬浮球显示方式选项（id 与 `ui.overlay.displayMode` 原值一致）。 */
@Composable
private fun DisplayModeOptions(): List<OptionItem> =
    listOf(
        OptionItem(id = DISPLAY_MODE_AUTO, label = stringResource(R.string.me_overlay_display_mode_auto)),
        OptionItem(id = DISPLAY_MODE_ALWAYS, label = stringResource(R.string.me_overlay_display_mode_always)),
        OptionItem(id = DISPLAY_MODE_EDGE, label = stringResource(R.string.me_overlay_display_mode_edge)),
    )

/** 悬浮球显示方式显示文案。 */
@Composable
private fun DisplayModeLabel(value: String): String =
    when (value) {
        DISPLAY_MODE_ALWAYS -> stringResource(R.string.me_overlay_display_mode_always)
        DISPLAY_MODE_EDGE -> stringResource(R.string.me_overlay_display_mode_edge)
        else -> stringResource(R.string.me_overlay_display_mode_auto)
    }

/** 悬浮窗通道选项（id 与 `ui.overlay.windowType` 原值一致，docs/tech/05 §10）。 */
@Composable
private fun WindowTypeOptions(): List<OptionItem> =
    listOf(
        OptionItem(id = WINDOW_TYPE_AUTO, label = stringResource(R.string.me_overlay_channel_auto)),
        OptionItem(id = WINDOW_TYPE_ACCESSIBILITY, label = stringResource(R.string.me_overlay_channel_accessibility)),
        OptionItem(id = WINDOW_TYPE_APPLICATION, label = stringResource(R.string.me_overlay_channel_application)),
    )

/** 悬浮窗通道显示文案。 */
@Composable
private fun WindowTypeLabel(value: String): String =
    when (value) {
        WINDOW_TYPE_AUTO -> stringResource(R.string.me_overlay_channel_auto)
        WINDOW_TYPE_ACCESSIBILITY -> stringResource(R.string.me_overlay_channel_accessibility)
        else -> stringResource(R.string.me_overlay_channel_application)
    }

/** shell 注入通道状态文案（N200）。 */
private fun shellChannelStatusRes(state: ShellState): Int =
    when (state) {
        ShellState.Disconnected -> R.string.me_shell_status_off
        ShellState.Connecting -> R.string.me_shell_status_connecting
        ShellState.Connected -> R.string.me_shell_status_on
    }

/** `ui.overlay.windowType` 取值（docs/tech/05 §10 双通道）。 */
private const val WINDOW_TYPE_AUTO = "auto"

/** 无障碍窗口通道。 */
private const val WINDOW_TYPE_ACCESSIBILITY = "accessibility"

/** 普通悬浮窗通道。 */
private const val WINDOW_TYPE_APPLICATION = "application"

/** `ui.overlay.displayMode` 取值：静置自动收拢（贴边→细条）。 */
private const val DISPLAY_MODE_AUTO = "auto"

/** 保持展开（不自动收拢）。 */
private const val DISPLAY_MODE_ALWAYS = "always"

/** 松手即收边细条。 */
private const val DISPLAY_MODE_EDGE = "edge"

/** 透明度可选档位（1.0=完全不透明）。 */
private val OPACITY_OPTIONS: List<Double> = listOf(1.0, 0.9, 0.8, 0.7, 0.6)

/** 小数透明度 → 百分比。 */
private const val PERCENT = 100

/** 导航应用选择弹层：自动 / 每次询问 / 已安装的具体应用（intent-tool §11）。 */
@Composable
internal fun NavigationSheet(
    selectedId: String,
    options: List<NavigationOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val items =
        listOf(
            OptionItem(id = NAVIGATION_AUTO, label = stringResource(R.string.me_navigation_auto)),
            OptionItem(id = NAVIGATION_ASK, label = stringResource(R.string.me_navigation_ask)),
        ) + options.filter { it.installed }.map { OptionItem(id = it.id, label = it.label) }
    OptionSheet(
        title = stringResource(R.string.me_nav_navigation_app),
        options = items,
        selectedId = selectedId,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** 导航应用行的显示文案；未知值按自动处理，已选但未安装的应用标注“将自动选择”。 */
@Composable
private fun navigationLabel(
    preferred: String,
    apps: List<NavigationOption>,
): String =
    when (preferred) {
        NAVIGATION_ASK -> stringResource(R.string.me_navigation_ask)
        NAVIGATION_AUTO -> stringResource(R.string.me_navigation_auto)
        else -> {
            val app = apps.firstOrNull { it.id == preferred }
            when {
                app == null -> stringResource(R.string.me_navigation_auto)
                app.installed -> app.label
                else -> stringResource(R.string.me_navigation_not_installed, app.label)
            }
        }
    }

/** 导航偏好的保留值（与 `device.intent.preferredMap` 一致）。 */
private const val NAVIGATION_AUTO = "auto"
private const val NAVIGATION_ASK = "ask"
