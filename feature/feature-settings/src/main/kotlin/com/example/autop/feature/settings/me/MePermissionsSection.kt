/**
 * "我的"页权限与运行分组（docs/tech/08 §4.5）：权限检查入口、悬浮窗通道
 * （`ui.overlay.windowType`，docs/tech/05 §10）、常驻后台（`task.host.persistent`）、
 * 任务结束后返回原应用（`task.returnToOriginApp`）与运行预算子页入口
 * （`agent.budget`，me/budget）。
 * 通道选择写配置后由 OverlayChannelResolver 的变化流重建窗口（立即生效）。
 * N186：原"触摸屏幕时暂停任务"开关随触摸盾落地移除（执行期固定屏蔽用户触摸）。
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
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.SettingsSection

/** 权限与运行分组：权限检查入口 + 悬浮窗通道/悬浮球显隐、显示方式与透明度 + 常驻后台/返回原应用开关 + 运行预算入口。 */
@Composable
internal fun PermissionsSection(
    state: MeUiState,
    onOpen: (SettingsSection) -> Unit,
    onOpenWindowTypeSheet: () -> Unit,
    onOpenDisplayModeSheet: () -> Unit,
    onOpenOpacitySheet: () -> Unit,
    onOpenNavigationSheet: () -> Unit,
    onOpenActionDelaySheet: () -> Unit,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_section_permissions))
        NavRow(
            title = stringResource(R.string.me_nav_permission_check),
            subtitle = null,
            onClick = { onOpen(SettingsSection.PERMISSIONS) },
        )
        NavRow(
            title = stringResource(R.string.me_nav_budget),
            subtitle = null,
            onClick = { onOpen(SettingsSection.BUDGET) },
        )
        OptionRow(
            title = stringResource(R.string.me_overlay_channel),
            subtitle = stringResource(R.string.me_sub_overlay_channel),
            value = WindowTypeLabel(state.overlayWindowType),
            onClick = onOpenWindowTypeSheet,
        )
        SwitchRow(
            title = stringResource(R.string.me_overlay_enabled),
            subtitle = null,
            checked = state.overlayEnabled,
            onCheckedChange = { onAction(MeAction.SetOverlayEnabled(it)) },
        )
        OptionRow(
            title = stringResource(R.string.me_overlay_display_mode),
            subtitle = stringResource(R.string.me_sub_display_mode),
            value = DisplayModeLabel(state.overlayDisplayMode),
            onClick = onOpenDisplayModeSheet,
        )
        OptionRow(
            title = stringResource(R.string.me_overlay_opacity),
            subtitle = null,
            value = opacityLabel(state.overlayOpacity),
            onClick = onOpenOpacitySheet,
        )
        NavRow(
            title = stringResource(R.string.me_nav_shell),
            subtitle = stringResource(shellChannelStatusRes(state.shellChannel)),
            onClick = { onOpen(SettingsSection.SHELL) },
        )
        OptionRow(
            title = stringResource(R.string.me_nav_navigation_app),
            subtitle = stringResource(R.string.me_sub_navigation_app),
            value = navigationLabel(state.navigationPreferred, state.navigationApps),
            onClick = onOpenNavigationSheet,
        )
        BehaviorRows(
            state = state,
            onOpenActionDelaySheet = onOpenActionDelaySheet,
            onAction = onAction,
        )
    }
}

/** 运行行为开关：常驻后台 / 结束后返回原应用 / 执行期触摸盾 + 动作后等待。 */
@Composable
private fun BehaviorRows(
    state: MeUiState,
    onOpenActionDelaySheet: () -> Unit,
    onAction: (MeAction) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.me_host_persistent),
        subtitle = stringResource(R.string.me_host_persistent_desc),
        checked = state.hostPersistent,
        onCheckedChange = { onAction(MeAction.SetHostPersistent(it)) },
    )
    SwitchRow(
        title = stringResource(R.string.me_return_to_origin),
        subtitle = null,
        checked = state.returnToOriginApp,
        onCheckedChange = { onAction(MeAction.SetReturnToOriginApp(it)) },
    )
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
        onClick = onOpenActionDelaySheet,
    )
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
@Composable
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
