package com.example.autop.core.ui.component

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
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.LocalSpacing

/**
 * 输入框旁的模型切换（会话级覆盖，ADR-0022）：芯片显示当前生效模型，点击弹出模型列表。
 *
 * @param selectedId 会话级覆盖的 ModelProfile id；null 表示跟随全局默认。
 * @param profiles 可选的模型配置。
 * @param enabled 运行中等场景传 false，芯片仍显示但不可打开。
 * @param onSelect 选择结果；选中默认模型时回落为 null（继续跟随全局默认）。
 * @param defaultProfileId 全局默认模型 id；列表中该项带"默认"标签。
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
    val defaultProfile = profiles.firstOrNull { it.id == defaultProfileId }
    val shownProfile = selectedId?.let { id -> profiles.firstOrNull { it.id == id } } ?: defaultProfile
    val label =
        when {
            profiles.isEmpty() -> stringResource(R.string.core_ui_model_none)
            shownProfile != null -> shownProfile.label
            else -> stringResource(R.string.core_ui_model_default)
        }
    val showDefaultTag = shownProfile != null && shownProfile.id == defaultProfileId
    AutopChip(
        text = label,
        selected = false,
        onClick = { if (enabled && profiles.isNotEmpty()) open = true },
        labelTrailing = {
            if (showDefaultTag) {
                AutopTag(text = stringResource(R.string.core_ui_model_default_tag), emphasized = true)
            }
            Text(
                text = ARROW,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = LocalSpacing.current.xs),
            )
        },
    )
    if (open) {
        val defaultTag = stringResource(R.string.core_ui_model_default_tag)
        val options =
            profiles.map { profile ->
                if (profile.id == defaultProfileId) profile.copy(tag = defaultTag) else profile
            }
        OptionSheet(
            title = stringResource(R.string.core_ui_model_title),
            options = options,
            selectedId = shownProfile?.id,
            onSelect = { id ->
                open = false
                onSelect(id.takeIf { it != defaultProfileId })
            },
            onDismiss = { open = false },
        )
    }
}

private const val ARROW = "▾"
