/**
 * 任务定时选择器（docs/tech/08 §4.3）：时/分/秒三列滚轮直接选时间（[TimeWheelDialog]），
 * 单次任务的日期走系统日历（[DateDialog]）。只负责展示与回调，取值由调用方写回表单。
 */
package com.example.autop.feature.tasks.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.autop.core.ui.theme.AutopAlpha
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.feature.tasks.R
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** 时间选择对话框：时/分/秒三列滚轮直接选，确认才写回。 */
@Composable
internal fun TimeWheelDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var second by remember { mutableIntStateOf(initial.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.task_edit_time_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WheelColumn(
                    label = stringResource(R.string.task_edit_hour),
                    values = HOURS,
                    selected = hour,
                    onSelect = { hour = it },
                )
                WheelColumn(
                    label = stringResource(R.string.task_edit_minute),
                    values = MINUTES,
                    selected = minute,
                    onSelect = { minute = it },
                )
                WheelColumn(
                    label = stringResource(R.string.task_edit_second),
                    values = MINUTES,
                    selected = second,
                    onSelect = { second = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(LocalTime.of(hour, minute, second)) },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.task_edit_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.small) {
                Text(text = stringResource(R.string.task_edit_picker_cancel))
            }
        },
    )
}

/** 日期选择对话框（单次任务）：系统日历；毫秒按 DatePicker 约定走 UTC 零点。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis ?: return@TextButton
                    onConfirm(Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate())
                },
            ) {
                Text(text = stringResource(R.string.task_edit_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.task_edit_picker_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/** 单列滚轮：可滚动/可点选，停稳后把居中项回调出去（[WHEEL_VISIBLE_ROWS] 行、居中高亮）。 */
@Composable
private fun WheelColumn(
    label: String,
    values: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val itemHeight = AutopDimens.wheelItemHeight
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val scope = rememberCoroutineScope()
    var center by remember { mutableIntStateOf(selected) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val settled = index + ((offset + itemHeightPx / 2f) / itemHeightPx).toInt()
                if (settled != center) {
                    center = settled
                    onSelect(settled)
                }
            }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier =
                Modifier
                    .width(AutopDimens.wheelColumnWidth)
                    .height(itemHeight * WHEEL_VISIBLE_ROWS),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = AutopAlpha.OPTION_FILL),
                            shape = MaterialTheme.shapes.small,
                        ),
            )
            LazyColumn(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                contentPadding = PaddingValues(vertical = itemHeight * WHEEL_PADDING_ROWS),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(values.size) { index ->
                    WheelItem(
                        text = values[index],
                        highlighted = index == center,
                        onClick = { scope.launch { listState.animateScrollToItem(index) } },
                    )
                }
            }
        }
    }
}

/** 滚轮项：居中项加粗 primary，点击滚到该项。 */
@Composable
private fun WheelItem(
    text: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .height(AutopDimens.wheelItemHeight)
                .fillMaxWidth()
                .clickable(onClick = onClick),
    )
}

/** 滚轮可见行数（居中行上下各留 [WHEEL_PADDING_ROWS] 行）。 */
private const val WHEEL_VISIBLE_ROWS: Int = 5

/** 滚轮上下留白行数（contentPadding 用，保证首项能滚到居中位）。 */
private const val WHEEL_PADDING_ROWS: Int = 2

/** 时/分/秒取值文本（00–59）。 */
private val MINUTES: List<String> = (0..59).map { it.toString().padStart(2, '0') }

/** 小时取值文本（00–23）。 */
private val HOURS: List<String> = (0..23).map { it.toString().padStart(2, '0') }
