/**
 * "我的"二级页的选择弹层（docs/tech/08 §4.5）。
 *
 * 各二级页共用一份 [MeSheetState]：设置行点击时置位对应开关，[MeSheetHost] 渲染弹层，
 * 选中或关闭后复位。弹层只在对应开关为真时组合，不占用其他页面的状态。
 */
package com.example.autop.feature.settings.me

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.feature.settings.R

/** 渲染二级页的全部选择弹层：选中后派发对应动作并关闭。 */
@Composable
internal fun MeSheetHost(
    sheets: MeSheetState,
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    val appearanceKind = sheets.appearance
    AppearanceSheetHost(
        kind = appearanceKind,
        appearance = state.appearance,
        onSelect = { id ->
            appearanceKind?.let { onAction(it.toAction(id)) }
            sheets.appearance = null
        },
        onDismiss = { sheets.appearance = null },
    )
    OverlaySheets(sheets = sheets, state = state, onAction = onAction)
    RuntimeSheets(sheets = sheets, state = state, onAction = onAction)
}

/** 悬浮球与导航应用相关弹层。 */
@Composable
private fun OverlaySheets(
    sheets: MeSheetState,
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    if (sheets.windowType) {
        WindowTypeSheet(
            selectedId = state.overlayWindowType,
            onSelect = { id ->
                onAction(MeAction.SetOverlayWindowType(id))
                sheets.windowType = false
            },
            onDismiss = { sheets.windowType = false },
        )
    }
    if (sheets.displayMode) {
        DisplayModeSheet(
            selectedId = state.overlayDisplayMode,
            onSelect = { id ->
                onAction(MeAction.SetOverlayDisplayMode(id))
                sheets.displayMode = false
            },
            onDismiss = { sheets.displayMode = false },
        )
    }
    if (sheets.opacity) {
        OpacitySheet(
            selected = state.overlayOpacity,
            onSelect = { value ->
                onAction(MeAction.SetOverlayOpacity(value))
                sheets.opacity = false
            },
            onDismiss = { sheets.opacity = false },
        )
    }
    if (sheets.navigation) {
        NavigationSheet(
            selectedId = state.navigationPreferred,
            options = state.navigationApps,
            onSelect = { id ->
                onAction(MeAction.SetNavigationApp(id))
                sheets.navigation = false
            },
            onDismiss = { sheets.navigation = false },
        )
    }
}

/** 任务提醒与动作等待相关弹层。 */
@Composable
private fun RuntimeSheets(
    sheets: MeSheetState,
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    if (sheets.snooze) {
        SnoozeSheet(
            options = state.snoozeOptions,
            selected = state.snoozeMinutes,
            onSelect = { minutes ->
                onAction(MeAction.SetSnoozeMinutes(minutes))
                sheets.snooze = false
            },
            onDismiss = { sheets.snooze = false },
        )
    }
    if (sheets.actionDelay) {
        ActionDelaySheet(
            selectedMs = state.actionDelayMs,
            onSelect = { seconds ->
                onAction(MeAction.SetActionDelay(seconds))
                sheets.actionDelay = false
            },
            onDismiss = { sheets.actionDelay = false },
        )
    }
}

/** 默认稍后档位选择弹层（`task.reminder.snoozeOptions`）。 */
@Composable
private fun SnoozeSheet(
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.me_snooze_default),
        options =
            options.map { minutes ->
                OptionItem(
                    id = minutes.toString(),
                    label = stringResource(R.string.me_minutes, minutes),
                )
            },
        selectedId = selected.toString(),
        onSelect = { id -> id.toIntOrNull()?.let(onSelect) },
        onDismiss = onDismiss,
    )
}

/** 动作后等待选择弹层（`device.action.delayMs`，tool_optimize 03 §5.1）：当前值高亮。 */
@Composable
internal fun ActionDelaySheet(
    selectedMs: Long,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.settings_action_delay_title),
        options =
            ACTION_DELAY_OPTIONS.map { seconds ->
                OptionItem(
                    id = seconds.toString(),
                    label = stringResource(R.string.settings_action_delay_seconds, seconds),
                )
            },
        selectedId = (selectedMs / MILLIS_PER_SECOND).toString(),
        onSelect = { id -> id.toIntOrNull()?.let(onSelect) },
        onDismiss = onDismiss,
    )
}

/** 动作后等待可选档位（秒）。 */
private val ACTION_DELAY_OPTIONS: List<Int> = listOf(1, 2, 3, 5, 8, 10)

/** 毫秒换算（配置 `device.action.delayMs` 存毫秒，选项按秒给）。 */
internal const val MILLIS_PER_SECOND: Long = 1000
