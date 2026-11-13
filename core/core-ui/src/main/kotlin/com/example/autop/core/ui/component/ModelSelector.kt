package com.example.autop.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.LocalSpacing

/** 「跟随全局默认模型」的哨兵选项 id（不会与 ModelProfile id 冲突）。 */
public const val FOLLOW_DEFAULT_MODEL_ID: String = ""

/**
 * 输入框旁的模型切换（会话级覆盖，ADR-0022）：芯片显示当前选择，点击弹出选项表。
 *
 * @param selectedId 会话级覆盖的 ModelProfile id；null 表示跟随全局默认。
 * @param profiles 可选的模型配置（不含"跟随默认"，组件自行在首位加入）。
 * @param enabled 运行中等场景传 false，芯片仍显示但不可打开。
 * @param onSelect 选择结果；null 表示跟随默认。
 * @param defaultProfileId 全局默认模型 id；跟随默认时芯片直接显示该模型名并带"默认"标记。
 */
@Composable
public fun AutopModelSelector(
    selectedId: String?,
    profiles: List<OptionItem>,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    defaultProfileId: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.core_ui_model_default)
    val defaultProfile = profiles.firstOrNull { it.id == defaultProfileId }
    val followDefaultLabel = defaultProfile?.label ?: defaultLabel
    val options = listOf(OptionItem(id = FOLLOW_DEFAULT_MODEL_ID, label = defaultLabel)) + profiles
    val current = selectedId ?: FOLLOW_DEFAULT_MODEL_ID
    val shownProfile = selectedId?.let { id -> profiles.firstOrNull { it.id == id } } ?: defaultProfile
    val label =
        when {
            profiles.isEmpty() -> stringResource(R.string.core_ui_model_none)
            shownProfile != null -> shownProfile.label
            else -> followDefaultLabel
        }
    val showDefaultTag = shownProfile != null && shownProfile.id == defaultProfileId
    AutopChip(
        text = label,
        selected = false,
        onClick = { if (enabled && profiles.isNotEmpty()) open = true },
        labelTrailing = {
            if (showDefaultTag) DefaultTag()
            Text(
                text = ARROW,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = LocalSpacing.current.xs),
            )
        },
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

/** "默认"小标记：跟随全局默认时贴在模型名后。 */
@Composable
private fun DefaultTag() {
    Text(
        text = stringResource(R.string.core_ui_model_default_tag),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ).padding(horizontal = LocalSpacing.current.xs, vertical = DefaultTagPaddingV),
    )
}

private val DefaultTagPaddingV = 1.dp
private const val ARROW = "▾"
