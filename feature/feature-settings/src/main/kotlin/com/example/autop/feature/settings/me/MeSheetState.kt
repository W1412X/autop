/**
 * "我的"二级页的弹层开关（docs/tech/08 §4.5）。
 *
 * 设置行点击时置位对应开关，选中或关闭后复位；弹层由 [MeSheetHost] 渲染。
 */
package com.example.autop.feature.settings.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 二级页弹层开关：设置行点击时置位，选中或关闭后复位。 */
@Stable
internal class MeSheetState {
    /** 外观分组的选择弹层类型；null 表示没有外观弹层。 */
    var appearance: SheetKind? by mutableStateOf(null)
    var windowType: Boolean by mutableStateOf(false)
    var displayMode: Boolean by mutableStateOf(false)
    var opacity: Boolean by mutableStateOf(false)
    var navigation: Boolean by mutableStateOf(false)
    var snooze: Boolean by mutableStateOf(false)
    var actionDelay: Boolean by mutableStateOf(false)
}

/** 记住一份弹层开关，与二级页同生命周期。 */
@Composable
internal fun rememberMeSheetState(): MeSheetState = remember { MeSheetState() }
