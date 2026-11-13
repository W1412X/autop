/**
 * "我的"一级页健康状态卡（docs/tech/08 §4.5）：2×3 网格，前五格是无障碍、通知使用权、
 * 常驻后台、模型、注入通道，第六格是"权限检查"。每个状态格点击后直接前往对应的设置位置。
 *
 * 必需项是前四个，决定卡片是否进入异常色；注入通道是可选通道，未连接不影响卡片颜色。
 */
package com.example.autop.feature.settings.me

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled
import com.example.autop.device.ShellState
import com.example.autop.feature.settings.MeFocus
import com.example.autop.feature.settings.MeGroup
import com.example.autop.feature.settings.R
import com.example.autop.feature.settings.SettingsSection
import com.example.autop.feature.settings.SystemSettingsTarget

/** 状态格的数据：标题、状态文案、色调（null 表示纯文字不着色）与点击后的动作。 */
private class HealthTileSpec(
    val label: String,
    val status: String,
    val tone: StatusTone?,
    val action: MeAction,
)

/**
 * 健康状态卡（[state] 的状态格；[onAction] 接收格子点击，由路由层转成导航）。
 */
@Composable
internal fun HealthCard(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    val hostHealthy = state.hostRunning || !state.hostPersistent
    val requiredOk =
        listOf(
            state.accessibilityConnected,
            state.notificationListenerGranted,
            hostHealthy,
            state.defaultModelAssigned,
        )
    val okCount = requiredOk.count { it }
    val allOk = okCount == requiredOk.size
    val spacing = LocalSpacing.current
    val tiles = healthTiles(state)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .background(
                    color =
                        if (allOk) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CARD_ALPHA)
                        } else {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = CARD_ALPHA)
                        },
                    shape = MaterialTheme.shapes.medium,
                ).padding(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.me_health_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.me_health_summary, okCount, requiredOk.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tiles.chunked(TILES_PER_ROW).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                rowTiles.forEach { tile ->
                    HealthTile(
                        spec = tile,
                        onClick = { onAction(tile.action) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 六个格子的内容与顺序（前五格为状态，第六格为权限检查入口）。 */
@Composable
private fun healthTiles(state: MeUiState): List<HealthTileSpec> =
    listOf(
        requiredTile(
            label = R.string.me_health_accessibility,
            ok = state.accessibilityConnected,
            okRes = R.string.me_health_on,
            badRes = R.string.me_health_bad,
            action = MeAction.OpenSystem(SystemSettingsTarget.ACCESSIBILITY),
        ),
        requiredTile(
            label = R.string.me_health_listener,
            ok = state.notificationListenerGranted,
            okRes = R.string.me_health_granted,
            badRes = R.string.me_health_ungranted,
            action = MeAction.OpenSystem(SystemSettingsTarget.NOTIFICATION_LISTENER),
        ),
        hostTile(state),
        requiredTile(
            label = R.string.me_health_model,
            ok = state.defaultModelAssigned,
            okRes = R.string.me_health_assigned,
            badRes = R.string.me_health_unassigned,
            action = MeAction.OpenSection(SettingsSection.MODEL),
        ),
        shellTile(state.shellChannel),
        HealthTileSpec(
            label = stringResource(R.string.me_nav_permission_check),
            status = stringResource(R.string.me_health_check_all),
            tone = null,
            action = MeAction.OpenSection(SettingsSection.PERMISSIONS),
        ),
    )

/** 必需项格：正常为成功色，异常为阻断色。 */
@Composable
private fun requiredTile(
    @StringRes label: Int,
    ok: Boolean,
    @StringRes okRes: Int,
    @StringRes badRes: Int,
    action: MeAction,
): HealthTileSpec =
    HealthTileSpec(
        label = stringResource(label),
        status = stringResource(if (ok) okRes else badRes),
        tone = if (ok) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
        action = action,
    )

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

/** 常驻后台格：点击定位到二级页“权限与运行”的常驻开关。 */
@Composable
private fun hostTile(state: MeUiState): HealthTileSpec {
    val (statusRes, tone) = hostStatus(state)
    return HealthTileSpec(
        label = stringResource(R.string.me_health_host),
        status = stringResource(statusRes),
        tone = tone,
        action = MeAction.OpenGroup(MeGroup.PERMISSIONS_RUNTIME, MeFocus.HOST_PERSISTENT),
    )
}

/** 注入通道格：点击进入注入通道子页。 */
@Composable
private fun shellTile(shell: ShellState): HealthTileSpec {
    val (statusRes, tone) = shellStatus(shell)
    return HealthTileSpec(
        label = stringResource(R.string.me_health_shell),
        status = stringResource(statusRes),
        tone = tone,
        action = MeAction.OpenSection(SettingsSection.SHELL),
    )
}

/** 单个状态格：标题 + 状态徽标（或纯文字）+ 右侧箭头，整格可点，无障碍朗读“标题，状态，点击前往”。 */
@Composable
private fun HealthTile(
    spec: HealthTileSpec,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalSpacing.current
    val description = stringResource(R.string.me_health_tile_desc, spec.label, spec.status)
    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = AutopDimens.minTouchTarget)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                ).semantics(mergeDescendants = true) { contentDescription = description }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spec.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val tone = spec.tone
            if (tone != null) {
                StatusChip(text = spec.status, tone = tone)
            } else {
                Text(
                    text = spec.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = AutopIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(IconSize.Sm.scaled()),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 健康卡每行的格子数。 */
private const val TILES_PER_ROW = 2

private const val CARD_ALPHA = 0.55f
