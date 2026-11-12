/**
 * "我的"页健康状态卡（docs/tech/08 §4.5）：无障碍 / 通知监听 / 前台服务 / 模型
 * 四项 StatusChip；任一异常时卡片用 blocked 色系并带"去处理"（跳权限检查）。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopTextButton
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.settings.R

/** 健康状态卡（[state] 四项状态；异常时 [onOpenHealth] 跳权限检查页）。 */
@Composable
internal fun HealthCard(
    state: MeUiState,
    onOpenHealth: () -> Unit,
) {
    val hostHealthy = state.hostRunning || !state.hostPersistent
    val allOk =
        state.accessibilityConnected &&
            state.notificationListenerGranted &&
            hostHealthy &&
            state.defaultModelAssigned
    val spacing = LocalSpacing.current
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
                ).padding(spacing.lg),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            HealthChip(label = stringResource(R.string.me_health_accessibility), ok = state.accessibilityConnected)
            HealthChip(label = stringResource(R.string.me_health_listener), ok = state.notificationListenerGranted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            HostChip(running = state.hostRunning, persistent = state.hostPersistent)
            HealthChip(label = stringResource(R.string.me_health_model), ok = state.defaultModelAssigned)
        }
        if (!allOk) {
            AutopTextButton(
                text = stringResource(R.string.me_health_fix),
                onClick = onOpenHealth,
            )
        }
    }
}

/** 单项状态徽标（状态不只靠颜色区分：图标 + 文字，docs/tech/08 §6）。 */
@Composable
private fun HealthChip(
    label: String,
    ok: Boolean,
) {
    StatusChip(
        text = label + stringResource(if (ok) R.string.me_health_ok else R.string.me_health_bad),
        tone = if (ok) StatusTone.SUCCEEDED else StatusTone.BLOCKED,
    )
}

/**
 * 常驻服务三态徽标（N190）：运行中＝常驻已生效；按需＝未开「常驻后台」，空闲不跑是
 * 设计行为不算异常；未开启＝开了常驻却没跑（异常）。
 */
@Composable
private fun HostChip(
    running: Boolean,
    persistent: Boolean,
) {
    val status =
        when {
            running -> stringResource(R.string.me_health_host_running) to StatusTone.SUCCEEDED
            !persistent -> stringResource(R.string.me_health_host_ondemand) to StatusTone.NEUTRAL
            else -> stringResource(R.string.me_health_bad) to StatusTone.BLOCKED
        }
    StatusChip(text = stringResource(R.string.me_health_host) + status.first, tone = status.second)
}

private const val CARD_ALPHA = 0.55f
