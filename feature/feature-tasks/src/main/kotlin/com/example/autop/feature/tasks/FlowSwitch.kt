/**
 * 流程 Beta 开关（docs/adr/0030-beta-feature-switch.md）：各页面经 ViewModel 读取并输出为界面状态，
 * Screen 只读状态，不直接访问配置。
 */
package com.example.autop.feature.tasks

import com.example.autop.core.config.BetaFeature
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.FeaturesConfig

/** 流程功能是否开启（`features.flows`，默认关闭）。 */
internal fun ConfigSnapshot.flowsEnabled(): Boolean = this[FeaturesConfig.FEATURES].isEnabled(BetaFeature.FLOWS)
