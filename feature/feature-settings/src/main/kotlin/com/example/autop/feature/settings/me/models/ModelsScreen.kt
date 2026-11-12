/**
 * 模型配置页（docs/tech/08 §4.5 `me/models`）：上半部分角色配置（主 / 视觉 / 轻量，
 * 从已有模型配置中选择），下半部分 PagedList 模型配置列表（名称、协议 · 模型名、
 * 角色 StatusChip）；新建/编辑页（[ModelEditScreen]）共用同一目的地址，编辑态由
 * [ModelsUiState.draft] 驱动。
 */
package com.example.autop.feature.settings.me.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.component.OptionRow
import com.example.autop.core.ui.component.OptionSheet
import com.example.autop.core.ui.component.PagedList
import com.example.autop.core.ui.component.PagedListState
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.settings.R

/**
 * 模型配置页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onBack 返回"我的"页。
 * @param onAction 用户操作回调。
 */
@Composable
public fun ModelsScreen(
    state: ModelsUiState,
    onBack: () -> Unit,
    onAction: (ModelsAction) -> Unit,
) {
    val draft = state.draft
    if (draft != null) {
        ModelEditScreen(
            state = state,
            draft = draft,
            onBack = { onAction(ModelsAction.Cancel) },
            onAction = onAction,
        )
    } else {
        ModelsList(
            state = state,
            onBack = onBack,
            onAction = onAction,
        )
    }
}

@Composable
private fun ModelsList(
    state: ModelsUiState,
    onBack: () -> Unit,
    onAction: (ModelsAction) -> Unit,
) {
    var defaultSheet by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        SubPageTopBar(
            title = stringResource(R.string.models_title),
            onBack = onBack,
            actions = {
                AutopIconButton(
                    icon = AutopIcons.Add,
                    contentDescription = stringResource(R.string.models_new_cd),
                    onClick = { onAction(ModelsAction.New) },
                )
            },
        )
        DefaultModelRow(state = state, onOpen = { defaultSheet = true })
        if (state.profiles.isEmpty()) {
            EmptyState(text = stringResource(R.string.models_empty))
        } else {
            PagedList(
                items = state.profiles,
                state = PagedListState(endReached = true),
                onLoadMore = {},
            ) { profile ->
                ListRow(
                    title = profile.displayName,
                    subtitle = subtitleParts(protocolLabel(profile.protocol), profile.model),
                    leading = AutopIcons.Settings,
                    trailing = {
                        if (profile.id == state.defaultProfileId) {
                            StatusChip(
                                text = stringResource(R.string.model_default_badge),
                                tone = StatusTone.NEUTRAL,
                                icon = AutopIcons.Check,
                            )
                        }
                    },
                    modifier = Modifier.clickable { onAction(ModelsAction.Edit(profile.id)) },
                )
            }
        }
    }
    if (defaultSheet) {
        DefaultModelSheet(
            state = state,
            onSelect = { id ->
                onAction(ModelsAction.SetDefault(id.ifEmpty { null }))
                defaultSheet = false
            },
            onDismiss = { defaultSheet = false },
        )
    }
}

/** 默认模型选择弹层：全部已添加的配置 + "未设置"（空 id）。 */
@Composable
private fun DefaultModelSheet(
    state: ModelsUiState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionSheet(
        title = stringResource(R.string.model_default_title),
        options =
            state.profiles.map { OptionItem(id = it.id, label = it.displayName) } +
                OptionItem(id = "", label = stringResource(R.string.model_default_none)),
        selectedId = state.defaultProfileId.orEmpty(),
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** 默认模型入口行：当前默认模型名（未设置时显示“未设置”），点击打开选择列表。 */
@Composable
private fun DefaultModelRow(
    state: ModelsUiState,
    onOpen: () -> Unit,
) {
    OptionRow(
        title = stringResource(R.string.model_default_title),
        subtitle = stringResource(R.string.model_default_subtitle),
        value =
            state.profiles.firstOrNull { it.id == state.defaultProfileId }?.displayName
                ?: stringResource(R.string.model_default_none),
        onClick = onOpen,
    )
}

/** 协议显示名（docs/tech/03 §5 的两个协议 id）。 */
@Composable
internal fun protocolLabel(protocol: String): String =
    when (protocol) {
        PROTOCOL_OPENAI -> stringResource(R.string.protocol_openai)
        PROTOCOL_ANTHROPIC -> stringResource(R.string.protocol_anthropic)
        else -> protocol
    }

/** 协议 id（docs/tech/03 §5，与两适配器的 PROTOCOL_ID 一致）。 */
internal const val PROTOCOL_OPENAI: String = "openai"

internal const val PROTOCOL_ANTHROPIC: String = "anthropic"
