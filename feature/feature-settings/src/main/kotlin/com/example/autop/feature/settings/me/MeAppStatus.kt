/**
 * "我的"页顶部的应用状态列表：必需权限、注入通道、当前模型三行（替代原健康状态卡）。
 * 每行左侧图标、中间标题、右侧当前状态与箭头；点击直接进入对应页面。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSemanticColors
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.core.ui.theme.tone
import com.example.autop.device.ShellState
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.SettingsSection

/** 应用状态列表。点击行直接跳转到对应的设置页。 */
@Composable
internal fun AppStatusSection(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    val accessibilityOk = state.accessibilityConnected
    val (shellRes, shellTone) = shellStatus(state.shellChannel)
    val modelName = state.defaultModelName
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenEdge, vertical = spacing.sm),
    ) {
        AppStatusRow(
            icon = AutopIcons.PhoneAndroid,
            title = stringResource(R.string.me_app_status_permission),
            status = stringResource(if (accessibilityOk) R.string.me_health_on else R.string.me_health_bad),
            tone = if (accessibilityOk) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
            onClick = { onAction(MeAction.OpenSection(SettingsSection.PERMISSIONS)) },
        )
        HorizontalDivider()
        AppStatusRow(
            icon = AutopIcons.Link,
            title = stringResource(R.string.me_app_status_shell),
            status = stringResource(shellRes),
            tone = shellTone,
            onClick = { onAction(MeAction.OpenSection(SettingsSection.SHELL)) },
        )
        HorizontalDivider()
        AppStatusRow(
            icon = AutopIcons.Psychology,
            title = stringResource(R.string.me_app_status_model),
            status = modelName ?: stringResource(R.string.me_model_unassigned),
            tone = if (modelName != null) StatusTone.NEUTRAL else StatusTone.BLOCKED,
            onClick = { onAction(MeAction.OpenSection(SettingsSection.MODEL)) },
        )
    }
}

@Composable
private fun AppStatusRow(
    icon: ImageVector,
    title: String,
    status: String,
    tone: StatusTone,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = spacing.md),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSemanticColors.current.tone(tone).foreground,
            maxLines = 1,
        )
        Icon(
            imageVector = AutopIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.padding(start = spacing.xs).size(IconSize.Sm.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 常驻后台三态（N190）：运行中＝常驻已生效；按需＝未开“常驻后台”，空闲不跑是设计行为，
 * 不算异常；未开启＝开了常驻却没跑（异常）。返回（文案资源，色调）。
 */
internal fun hostStatus(state: MeUiState): Pair<Int, StatusTone> =
    when {
        state.hostRunning -> R.string.me_health_host_running to StatusTone.SUCCEEDED
        !state.hostPersistent -> R.string.me_health_host_ondemand to StatusTone.NEUTRAL
        else -> R.string.me_health_bad to StatusTone.BLOCKED
    }

/** 注入通道状态（可选通道）：已连接为成功色，连接中与未连接都用中性色，不拉红。返回（文案资源，色调）。 */
internal fun shellStatus(shell: ShellState): Pair<Int, StatusTone> =
    when (shell) {
        ShellState.Connected -> R.string.me_health_shell_on to StatusTone.SUCCEEDED
        ShellState.Connecting -> R.string.me_health_shell_connecting to StatusTone.NEUTRAL
        ShellState.Disconnected -> R.string.me_health_shell_off to StatusTone.NEUTRAL
    }
