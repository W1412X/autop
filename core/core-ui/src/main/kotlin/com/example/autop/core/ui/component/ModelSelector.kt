package com.example.autop.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.R

/** 「跟随全局默认模型」的哨兵选项 id（不会与 ModelProfile id 冲突）。 */
public const val FOLLOW_DEFAULT_MODEL_ID: String = ""

/**
 * 输入框旁的模型切换（会话级覆盖，ADR-0022）：芯片显示当前选择，点击弹出选项表。
 *
 * @param selectedId 会话级覆盖的 ModelProfile id；null 表示跟随全局默认。
 * @param profiles 可选的模型配置（不含"跟随默认"，组件自行在首位加入）。
 * @param enabled 运行中等场景传 false，芯片仍显示但不可打开。
 * @param onSelect 选择结果；null 表示跟随默认。
 */
@Composable
public fun AutopModelSelector(
    selectedId: String?,
    profiles: List<OptionItem>,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.core_ui_model_default)
    val options = listOf(OptionItem(id = FOLLOW_DEFAULT_MODEL_ID, label = defaultLabel)) + profiles
    val current = selectedId ?: FOLLOW_DEFAULT_MODEL_ID
    val label =
        if (profiles.isEmpty()) {
            stringResource(R.string.core_ui_model_none)
        } else {
            options.firstOrNull { it.id == current }?.label ?: defaultLabel
        }
    AutopChip(
        text = "$label ▾",
        selected = false,
        onClick = { if (enabled && profiles.isNotEmpty()) open = true },
    )
    if (open) {
        OptionSheet(
            title = stringResource(R.string.core_ui_model_title),
            options = options,
            selectedId = current,
            onSelect = { id ->
                open = false
                onSelect(id.takeIf { it != FOLLOW_DEFAULT_MODEL_ID })
            },
            onDismiss = { open = false },
        )
    }
}
