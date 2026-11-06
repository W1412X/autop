/**
 * "我的"页任务与提醒分组、关于分组（docs/tech/08 §4.5）：条件触发总开关、提醒铃声、
 * 默认稍后、提醒超时（只读）与版本信息。
 */
package com.example.autop.feature.settings.me

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.NavRow
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

/** 关于分组：版本号，以及项目地址、开发者主页、下载与更新三个外链（系统浏览器打开）。 */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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
        AboutLink(R.string.me_about_project, R.string.me_about_project_url, uriHandler)
        AboutLink(R.string.me_about_developer, R.string.me_about_developer_url, uriHandler)
        AboutLink(R.string.me_about_releases, R.string.me_about_releases_url, uriHandler)
    }
}

/** 外链行：副标题显示去掉协议头的地址；设备上没有浏览器时点击不做任何事（不崩溃）。 */
@Composable
private fun AboutLink(
    @StringRes title: Int,
    @StringRes url: Int,
    uriHandler: UriHandler,
) {
    val link = stringResource(url)
    NavRow(
        title = stringResource(title),
        subtitle = link.removePrefix(URL_SCHEME),
        onClick = { runCatching { uriHandler.openUri(link) } },
    )
}

private const val URL_SCHEME: String = "https://"
