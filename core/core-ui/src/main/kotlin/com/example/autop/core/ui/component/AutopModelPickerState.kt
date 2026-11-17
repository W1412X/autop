package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** 模型选择器的展开状态：芯片与列表共用同一份（见 [AutopModelChip]、[AutopModelPicker]）。 */
@Stable
public class AutopModelPickerState internal constructor(
    open: Boolean,
) {
    /** 列表是否展开。 */
    public var open: Boolean by mutableStateOf(open)
}

/** 记住一份模型选择器的展开状态（列表收起）。 */
@Composable
public fun rememberAutopModelPickerState(): AutopModelPickerState = remember { AutopModelPickerState(open = false) }
