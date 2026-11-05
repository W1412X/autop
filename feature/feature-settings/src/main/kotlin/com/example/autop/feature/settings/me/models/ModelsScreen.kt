/**
 * 模型配置页（docs/tech/08 §4.5 `me/models`）：PagedList 列表（名称、协议 · 模型名、
 * 角色 StatusChip）与新建/编辑页（[ModelEditScreen]）共用同一目的地址，
 * 编辑态由 [ModelsUiState.draft] 驱动。
 */
package com.example.autop.feature.settings.me.models

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.AutopIconButton
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.component.EmptyState
import com.example.autop.core.ui.component.ListRow
import com.example.autop.core.ui.component.PagedList
import com.example.autop.core.ui.component.PagedListState
import com.example.autop.core.ui.component.StatusChip
import com.example.autop.core.ui.component.SubPageTopBar
import com.example.autop.core.ui.component.subtitleParts
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.feature.settings.R
import com.example.autop.llm.ModelRole
import com.example.autop.llm.ModelRoles

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
                    trailing = { RoleChips(roles = state.roles, profileId = profile.id) },
                    modifier = Modifier.clickable { onAction(ModelsAction.Edit(profile.id)) },
                )
            }
        }
    }
}

/** 角色徽标（docs/tech/08 §4.5：主 / 视觉 / 轻量，StatusChip 自带图标与文字）。 */
@Composable
private fun RoleChips(
    roles: ModelRoles,
    profileId: String,
) {
    Row {
        for (labelRes in roles.labelResOf(profileId)) {
            StatusChip(
                text = stringResource(labelRes),
                tone = StatusTone.NEUTRAL,
                icon = AutopIcons.Check,
            )
        }
    }
}

/** 该 profile 担任角色的文案资源（docs/tech/08 §4.5 列表行"角色 StatusChip(主/视觉/轻量)"）。 */
private fun ModelRoles.labelResOf(id: String): List<Int> =
    buildList {
        if (main == id) add(R.string.model_role_main)
        if (vision == id) add(R.string.model_role_vision)
        if (light == id) add(R.string.model_role_light)
    }

/** 协议显示名（docs/tech/03 §5 的两个协议 id）。 */
@Composable
internal fun protocolLabel(protocol: String): String =
    when (protocol) {
        PROTOCOL_OPENAI -> stringResource(R.string.protocol_openai)
        PROTOCOL_ANTHROPIC -> stringResource(R.string.protocol_anthropic)
        else -> protocol
    }

/** 角色显示名（[ModelRole] 顺序）。 */
@StringRes
internal fun roleLabelRes(role: ModelRole): Int =
    when (role) {
        ModelRole.MAIN -> R.string.model_role_main_label
        ModelRole.VISION -> R.string.model_role_vision_label
        ModelRole.LIGHT -> R.string.model_role_light_label
    }

/** 协议 id（docs/tech/03 §5，与两适配器的 PROTOCOL_ID 一致）。 */
internal const val PROTOCOL_OPENAI: String = "openai"

internal const val PROTOCOL_ANTHROPIC: String = "anthropic"
