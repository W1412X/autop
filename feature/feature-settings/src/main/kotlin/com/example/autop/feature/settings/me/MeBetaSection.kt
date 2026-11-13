/**
 * "我的"页 Beta 功能分组（docs/adr/0030-beta-feature-switch.md）。
 *
 * 每个 Beta 功能一个开关，写入 `features.*`；功能关闭时对应入口在其他页面隐藏。
 */
package com.example.autop.feature.settings.me

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.core.config.BetaFeature
import com.example.autop.core.ui.component.SectionTitle
import com.example.autop.core.ui.component.SwitchRow
import com.example.autop.feature.settings.R

/** Beta 功能分组：当前只有“流程”开关。 */
@Composable
internal fun BetaSection(
    state: MeUiState,
    onAction: (MeAction) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.me_section_beta))
        SwitchRow(
            title = stringResource(R.string.me_beta_flows),
            subtitle = stringResource(R.string.me_sub_beta_flows),
            checked = state.flowsEnabled,
            onCheckedChange = { onAction(MeAction.SetBetaFeature(BetaFeature.FLOWS, it)) },
        )
    }
}
