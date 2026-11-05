/**
 * 条件触发的展示文案（docs/tech/08 §4.3）：事件源中文名，列表 / 详情 / 编辑页共用。
 * 内置源 id 见 docs/tech/07 §1.4（`trigger.*`），未知 id 由调用方回落显示原 id。
 */
package com.example.autop.feature.tasks

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** 事件源中文名资源；未知 id 给 0（调用方回落显示原 id）。 */
@StringRes
internal fun sourceLabelRes(id: String): Int =
    when (id) {
        "trigger.notification" -> R.string.task_source_notification
        "trigger.app_foreground" -> R.string.task_source_foreground
        "trigger.charging" -> R.string.task_source_charging
        "trigger.network" -> R.string.task_source_network
        "trigger.screen_unlock" -> R.string.task_source_unlock
        else -> 0
    }

/** 事件源中文名（未知 id 原样显示）。 */
@Composable
internal fun sourceLabel(id: String): String {
    val labelRes = sourceLabelRes(id)
    return if (labelRes == 0) id else stringResource(labelRes)
}
