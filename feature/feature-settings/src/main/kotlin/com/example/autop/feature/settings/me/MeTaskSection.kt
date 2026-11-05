/**
 * "我的"页任务与提醒分组、关于分组（docs/tech/08 §4.5）：条件触发总开关、提醒铃声、
 * 默认稍后、提醒超时（只读）与版本信息。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.feature.settings.R

/** 任务与提醒分组（docs/tech/08 §4.5）。 */
@Composable
internal fun TasksReminderSection(
    state: MeUiState,
    onOpenSnoozeSheet: () -> Unit,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_section_tasks))
        SwitchRow(
            title = stringResource(R.string.me_conditions_enabled),
            subtitle = null,
            checked = state.conditionsEnabled,
            onCheckedChange = { onAction(MeAction.SetConditionsEnabled(it)) },
        )
        ActionRow(
            title = stringResource(R.string.me_reminder_sound),
            subtitle = null,
            onClick = { onAction(MeAction.OpenReminderSound) },
        )
        OptionRow(
            title = stringResource(R.string.me_snooze_default),
            subtitle = null,
            value = stringResource(R.string.me_minutes, state.snoozeMinutes),
            onClick = onOpenSnoozeSheet,
        )
        InfoRow(
            title = stringResource(R.string.me_reminder_expire),
            subtitle = null,
            value =
                stringResource(
                    R.string.me_reminder_expire_value,
                    state.reminderExpireSchedule,
                    state.reminderExpireCondition,
                ),
        )
    }
}

/** 关于分组（docs/tech/08 §4.5：InfoRow(版本)）。 */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val version =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
            }.getOrDefault("-")
        }
    Column {
        SectionTitle(text = stringResource(R.string.me_section_about))
        InfoRow(
            title = stringResource(R.string.me_version),
            subtitle = null,
            value = version,
        )
    }
}
