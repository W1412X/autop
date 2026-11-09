/**
 * "我的"页任务与提醒分组、关于分组（docs/tech/08 §4.5）：条件触发总开关、提醒铃声、
 * 默认稍后、提醒超时（只读）与版本信息。
 */
package com.example.autop.feature.settings.me

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.ActionRow
import com.example.autop.core.ui.component.AutopTonalButton
import com.example.autop.core.ui.component.InfoRow
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.StatusBanner
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.core.ui.theme.LocalSpacing
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.device.UpdateState
import com.example.autop.feature.settings.R
import java.util.Locale

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
            subtitle = stringResource(R.string.me_sub_conditions_enabled),
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

/** 关于分组：版本号、检查更新（新版本横幅/下载进度/安装），以及项目地址、开发者主页、下载与更新三个外链。 */
@Composable
internal fun AboutSection(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
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
        UpdateSection(state = state, currentVersion = version, onAction = onAction)
        AboutLink(R.string.me_about_project, R.string.me_about_project_url, uriHandler)
        AboutLink(R.string.me_about_developer, R.string.me_about_developer_url, uriHandler)
        AboutLink(R.string.me_about_releases, R.string.me_about_releases_url, uriHandler)
    }
}

/** 更新区：检查更新行 + 新版本横幅（下载/安装按钮与进度）。 */
@Composable
private fun UpdateSection(
    state: MeUiState,
    currentVersion: String,
    onAction: (MeAction) -> Unit,
) {
    val update = state.update
    val busy = update is UpdateState.Checking || update is UpdateState.Downloading
    ActionRow(
        title = stringResource(R.string.me_check_update),
        subtitle =
            when (update) {
                UpdateState.Checking -> stringResource(R.string.me_update_checking)
                is UpdateState.Downloading ->
                    if (update.progress >= 0f) {
                        stringResource(
                            R.string.me_update_downloading,
                            String.format(Locale.US, "%.0f%%", update.progress * PERCENT_SCALE),
                        )
                    } else {
                        stringResource(R.string.me_update_downloading_unknown)
                    }
                else -> null
            },
        onClick = { if (!busy) onAction(MeAction.CheckUpdate) },
    )
    when (update) {
        is UpdateState.Available ->
            StatusBanner(
                tone = StatusTone.RUNNING,
                title = stringResource(R.string.me_update_available_title, update.info.versionName),
                message = stringResource(R.string.me_update_available_body, currentVersion),
                modifier = Modifier.padding(horizontal = LocalSpacing.current.screenEdge),
                actions = {
                    AutopTonalButton(
                        text = stringResource(R.string.me_update_download),
                        onClick = { onAction(MeAction.DownloadUpdate) },
                    )
                },
            )
        is UpdateState.ReadyToInstall ->
            StatusBanner(
                tone = StatusTone.SUCCEEDED,
                title = stringResource(R.string.me_update_available_title, update.info.versionName),
                message = stringResource(R.string.me_update_ready),
                modifier = Modifier.padding(horizontal = LocalSpacing.current.screenEdge),
                actions = {
                    AutopTonalButton(
                        text = stringResource(R.string.me_update_install),
                        onClick = { onAction(MeAction.InstallUpdate) },
                    )
                },
            )
        else -> Unit
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

/** 进度 0..1 → 百分比数值。 */
private const val PERCENT_SCALE: Int = 100
