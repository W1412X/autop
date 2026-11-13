/**
 * "我的"一级页（docs/tech/08 §4.5 两级结构）：健康状态卡 + 七个分类入口。
 *
 * 每个分类行的副标题显示当前值（如常驻后台状态、主模型名）；点击进入对应的二级页 [MeGroupScreen]。
 */
package com.example.autop.feature.settings.me

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.autop.core.ui.component.NavRow
import com.example.autop.core.ui.component.TopLevelHeader
import com.example.autop.feature.settings.MeGroup
import com.example.autop.feature.settings.R

/** 一级页分类顺序（docs/tech/08 §4.5）。 */
private val GROUP_ORDER: List<MeGroup> =
    listOf(
        MeGroup.MODEL,
        MeGroup.PERMISSIONS_RUNTIME,
        MeGroup.SECURITY,
        MeGroup.TASKS,
        MeGroup.APPEARANCE,
        MeGroup.ADVANCED,
        MeGroup.ABOUT,
    )

/**
 * "我的"一级页（无状态，docs/standards/coding-standards §8）。
 *
 * @param state 页面状态。
 * @param onAction 用户操作回调（状态格与分类行都经 [MeAction.OpenGroup] / [MeAction.OpenSection] / [MeAction.OpenSystem]）。
 */
@Composable
public fun MeScreen(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    val version = rememberAppVersion()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { TopLevelHeader(title = stringResource(R.string.me_title)) }
            item { HealthCard(state = state, onAction = onAction) }
            items(GROUP_ORDER, key = { it.id }) { group ->
                NavRow(
                    title = stringResource(groupTitleRes(group)),
                    subtitle = groupSubtitle(group = group, state = state, version = version),
                    onClick = { onAction(MeAction.OpenGroup(group)) },
                )
            }
        }
    }
}

/** 分类标题（与 docs/tech/08 §4.5 的分组名一致，二级页顶栏同样使用）。 */
@StringRes
internal fun groupTitleRes(group: MeGroup): Int =
    when (group) {
        MeGroup.MODEL -> R.string.me_section_model
        MeGroup.PERMISSIONS_RUNTIME -> R.string.me_section_permissions
        MeGroup.SECURITY -> R.string.me_section_security
        MeGroup.TASKS -> R.string.me_section_tasks
        MeGroup.APPEARANCE -> R.string.me_section_appearance
        MeGroup.ADVANCED -> R.string.me_section_advanced
        MeGroup.ABOUT -> R.string.me_section_about
    }

/** 一级行的副标题：显示该分类的当前值，用户不进二级页也能看到异常（如常驻后台未开启）。 */
@Composable
private fun groupSubtitle(
    group: MeGroup,
    state: MeUiState,
    version: String,
): String =
    when (group) {
        MeGroup.MODEL -> state.defaultModelName ?: stringResource(R.string.me_model_unassigned)
        MeGroup.PERMISSIONS_RUNTIME ->
            stringResource(
                R.string.me_sub_group_runtime,
                stringResource(hostStatus(state).first),
                stringResource(shellStatus(state.shellChannel).first),
            )
        MeGroup.SECURITY -> stringResource(R.string.me_sub_group_security)
        MeGroup.TASKS -> stringResource(R.string.me_sub_group_tasks, stringResource(onOffRes(state.conditionsEnabled)))
        MeGroup.APPEARANCE ->
            stringResource(
                R.string.me_sub_group_appearance,
                SheetKind.DARK_MODE.labelOf(state.appearance.darkMode),
                SheetKind.SEED.labelOf(state.appearance.seedIndex.toString()),
            )
        MeGroup.ADVANCED -> stringResource(R.string.me_sub_group_advanced, stringResource(onOffRes(state.flowsEnabled)))
        MeGroup.ABOUT -> stringResource(R.string.me_sub_group_about, version)
    }

/** 开关状态文案。 */
@StringRes
internal fun onOffRes(on: Boolean): Int = if (on) R.string.me_state_on else R.string.me_state_off
