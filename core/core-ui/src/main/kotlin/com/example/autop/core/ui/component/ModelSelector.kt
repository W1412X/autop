package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.autop.core.ui.R
import com.example.autop.core.ui.theme.LocalSpacing

/**
 * 输入框旁的模型切换（会话级覆盖，ADR-0022）：芯片显示当前生效模型，点击展开模型列表。
 *
 * 对话页用这一组合：列表以弹层（[OptionSheet]）呈现。悬浮窗需要把芯片和列表放在不同位置，
 * 因此拆成 [AutopModelChip] 与 [AutopModelPicker] 两个可单独放置的部件，二者共用同一份
 * [AutopModelPickerState]，列表本体（[OptionList]）也是同一个组件。
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
    val state = rememberAutopModelPickerState()
    AutopModelChip(
        state = state,
        selectedId = selectedId,
        profiles = profiles,
        enabled = enabled,
        defaultProfileId = defaultProfileId,
    )
    AutopModelPicker(
        state = state,
        selectedId = selectedId,
        profiles = profiles,
        onSelect = onSelect,
        defaultProfileId = defaultProfileId,
        inline = false,
    )
}

/** 模型芯片：显示当前生效模型，点击切换列表的展开状态。 */
@Composable
public fun AutopModelChip(
    state: AutopModelPickerState,
    selectedId: String?,
    profiles: List<OptionItem>,
    enabled: Boolean,
    defaultProfileId: String? = null,
) {
    val shownProfile = shownProfileOf(selectedId, profiles, defaultProfileId)
    val label =
        when {
            profiles.isEmpty() -> stringResource(R.string.core_ui_model_none)
            shownProfile != null -> shownProfile.label
            else -> stringResource(R.string.core_ui_model_default)
        }
    val showDefaultTag = shownProfile != null && shownProfile.id == defaultProfileId
    AutopFlatChip(
        text = label,
        onClick = { if (enabled && profiles.isNotEmpty()) state.open = !state.open },
        labelTrailing = {
            if (showDefaultTag) {
                AutopTag(text = stringResource(R.string.core_ui_model_default_tag), emphasized = true)
            }
            Text(
                text = ARROW,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = LocalSpacing.current.xs),
            )
        },
    )
}

/**
 * 模型列表。[inline] 为假时以弹层呈现（对话页）；为真时就地展开，由调用方放在芯片下方（悬浮窗）。
 * 选中后收起并回调；选中默认模型时回调 null。
 */
@Composable
public fun AutopModelPicker(
    state: AutopModelPickerState,
    selectedId: String?,
    profiles: List<OptionItem>,
    onSelect: (String?) -> Unit,
    defaultProfileId: String? = null,
    inline: Boolean = false,
) {
    if (!state.open) return
    val defaultTag = stringResource(R.string.core_ui_model_default_tag)
    val title = stringResource(R.string.core_ui_model_title)
    val options =
        profiles.map { profile ->
            if (profile.id == defaultProfileId) profile.copy(tag = defaultTag) else profile
        }
    val shownId = shownProfileOf(selectedId, profiles, defaultProfileId)?.id
    val pick: (String) -> Unit = { id ->
        state.open = false
        onSelect(id.takeIf { it != defaultProfileId })
    }
    if (inline) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = MODEL_PICKER_MAX_HEIGHT),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            OptionList(title = title, options = options, selectedId = shownId, onPick = pick)
        }
    } else {
        OptionSheet(
            title = title,
            options = options,
            selectedId = shownId,
            onSelect = pick,
            onDismiss = { state.open = false },
        )
    }
}

/** 当前生效的模型：会话级覆盖优先，否则回落到全局默认。 */
private fun shownProfileOf(
    selectedId: String?,
    profiles: List<OptionItem>,
    defaultProfileId: String?,
): OptionItem? {
    val defaultProfile = profiles.firstOrNull { it.id == defaultProfileId }
    return selectedId?.let { id -> profiles.firstOrNull { it.id == id } } ?: defaultProfile
}

/** 内嵌模型列表的最大高度（超出后列表内滚动）。 */
private val MODEL_PICKER_MAX_HEIGHT: Dp = 240.dp

private const val ARROW = "▾"
