/**
 * 流程 Beta 开关的读取（docs/adr/0030-beta-feature-switch.md）。任务域的流程入口统一经这里判断，
 * 不在各处重复写配置键。
 */
package com.example.autop.task.impl

import com.example.autop.core.config.BetaFeature
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.FeaturesConfig

/** 流程功能是否开启（`features.flows`，默认关闭）。 */
internal fun ConfigSnapshot.flowsEnabled(): Boolean = this[FeaturesConfig.FEATURES].isEnabled(BetaFeature.FLOWS)

/** 提示词模板中流程段落的条件变量名（与 prompts 目录下 task 模板头部的 vars 声明一致）。 */
internal const val FLOWS_PROMPT_VAR: String = "flows"

/** 流程段落的渲染变量：开启传非空值，关闭传空串（`{{#if flows}}` 对空串视为假）。 */
internal fun flowsPromptVars(enabled: Boolean): Map<String, String> =
    mapOf(FLOWS_PROMPT_VAR to if (enabled) "1" else "")
