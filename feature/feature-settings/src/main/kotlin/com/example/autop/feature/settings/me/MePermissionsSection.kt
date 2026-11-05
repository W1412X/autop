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

/** 权限与运行分组：权限检查入口 + 悬浮窗通道 + 常驻后台/返回原应用开关 + 运行预算入口。 */
@Composable
internal fun PermissionsSection(
    state: MeUiState,
    onOpen: (SettingsSection) -> Unit,
    onOpenWindowTypeSheet: () -> Unit,
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
            subtitle = null,
            value = WindowTypeLabel(state.overlayWindowType),
            onClick = onOpenWindowTypeSheet,
        )
        NavRow(
            title = stringResource(R.string.me_nav_shell),
            subtitle = stringResource(shellChannelStatusRes(state.shellChannel)),
            onClick = { onOpen(SettingsSection.SHELL) },
        )
        SwitchRow(
            title = stringResource(R.string.me_host_persistent),
            subtitle = null,
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
