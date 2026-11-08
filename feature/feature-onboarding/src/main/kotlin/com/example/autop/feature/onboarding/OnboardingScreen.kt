/**
 * 引导与健康检查页（docs/tech/08 §4.7）：卡片列表（surfaceVariant α0.55 底、
 * medium 圆角、内边距 lg），左侧状态图标（完成 CheckCircle / 未完成
 * RadioButtonUnchecked / 必需项未完成 ErrorOutline），底部"开始使用"。
 * health 模式：标题"权限检查"，无底部按钮。
 */
package com.example.autop.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.TopLevelHeader
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.scaled

/**
 * 引导与健康检查页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param healthMode health 模式（标题"权限检查"、无底部按钮）。
 * @param onAction 用户操作回调。
 */
@Composable
public fun OnboardingScreen(
    state: OnboardingUiState,
    healthMode: Boolean,
    onAction: (OnboardingAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        TopLevelHeader(
            title = stringResource(if (healthMode) R.string.onboarding_health_title else R.string.onboarding_title),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.items.filter { it.visible }) { item ->
                CheckCard(
                    item = item,
                    restrictedSettingsNote = item.id == CheckId.ACCESSIBILITY && state.restrictedSettings,
                    onAction = onAction,
                )
            }
        }
        if (!healthMode) {
            AutopButton(
                text = stringResource(R.string.onboarding_start),
                onClick = { onAction(OnboardingAction.Complete) },
                enabled = state.requiredDone && !state.completed,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(LocalSpacing.current.lg)
                        .navigationBarsPadding(),
            )
        }
    }
}

/** 一张检查卡片。 */
@Composable
private fun CheckCard(
    item: CheckItem,
    restrictedSettingsNote: Boolean,
    onAction: (OnboardingAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CARD_ALPHA),
                    shape = MaterialTheme.shapes.medium,
                ).padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = statusIcon(item),
            contentDescription = null,
            modifier = Modifier.size(IconSize.Md.scaled()),
            tint = statusTint(item),
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes(item.id)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(descriptionRes(item.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CheckNotes(item = item, restrictedSettingsNote = restrictedSettingsNote)
        }
        Spacer(modifier = Modifier.width(spacing.md))
        AutopTonalButton(
            text = stringResource(R.string.onboarding_go_settings),
            onClick = { onAction(OnboardingAction.OpenCheck(item.id)) },
        )
    }
}

/** 卡片附加提示（受限设置/注入通道引导，仅相关卡片显示）。 */
@Composable
private fun CheckNotes(
    item: CheckItem,
    restrictedSettingsNote: Boolean,
) {
    if (restrictedSettingsNote) {
        Text(
            text = stringResource(R.string.onboarding_restricted_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (item.id == CheckId.SHELL_PAIRING) {
        Text(
            text = stringResource(R.string.onboarding_check_shell_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (item.id == CheckId.ACCESSIBILITY) {
        // 可选增强：只提示，不作为必需设置（不配对也能正常使用无障碍注入）。
        Text(
            text = stringResource(R.string.onboarding_shell_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 状态图标（完成 CheckCircle / 未完成 RadioButtonUnchecked / 必需项未完成 ErrorOutline）。 */
private fun statusIcon(item: CheckItem): ImageVector =
    when {
        item.done -> AutopIcons.CheckCircle
        item.required -> AutopIcons.Error
        else -> AutopIcons.RadioButtonUnchecked
    }

/** 状态色（同图标规则；状态不只靠颜色区分，docs/tech/08 §6）。 */
@Composable
private fun statusTint(item: CheckItem): Color =
    when {
        item.done -> MaterialTheme.colorScheme.primary
        item.required -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

@StringRes
private fun titleRes(id: CheckId): Int =
    when (id) {
        CheckId.MODEL -> R.string.onboarding_check_model_title
        CheckId.ACCESSIBILITY -> R.string.onboarding_check_accessibility_title
        CheckId.SHELL_PAIRING -> R.string.onboarding_check_shell_title
        CheckId.OVERLAY -> R.string.onboarding_check_overlay_title
        CheckId.NOTIFICATIONS -> R.string.onboarding_check_notifications_title
        CheckId.NOTIFICATION_LISTENER -> R.string.onboarding_check_listener_title
        CheckId.FULL_SCREEN_INTENT -> R.string.onboarding_check_full_screen_intent_title
        CheckId.EXACT_ALARM -> R.string.onboarding_check_exact_alarm_title
        CheckId.BATTERY_OPTIMIZATIONS -> R.string.onboarding_check_battery_title
        CheckId.VENDOR_BACKGROUND -> R.string.onboarding_check_vendor_title
    }

@StringRes
private fun descriptionRes(id: CheckId): Int =
    when (id) {
        CheckId.MODEL -> R.string.onboarding_check_model_desc
        CheckId.ACCESSIBILITY -> R.string.onboarding_check_accessibility_desc
        CheckId.SHELL_PAIRING -> R.string.onboarding_check_shell_desc
        CheckId.OVERLAY -> R.string.onboarding_check_overlay_desc
        CheckId.NOTIFICATIONS -> R.string.onboarding_check_notifications_desc
        CheckId.NOTIFICATION_LISTENER -> R.string.onboarding_check_listener_desc
        CheckId.FULL_SCREEN_INTENT -> R.string.onboarding_check_full_screen_intent_desc
        CheckId.EXACT_ALARM -> R.string.onboarding_check_exact_alarm_desc
        CheckId.BATTERY_OPTIMIZATIONS -> R.string.onboarding_check_battery_desc
        CheckId.VENDOR_BACKGROUND -> R.string.onboarding_check_vendor_desc
    }

private const val CARD_ALPHA = 0.55f
