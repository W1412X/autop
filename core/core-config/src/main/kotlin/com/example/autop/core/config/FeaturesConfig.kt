/**
 * `features` 领域的配置段与键（docs/tech/09 §1.7）。
 * 段默认值与 `core-config-impl/assets/config/features.json` 必须一致（有测试）。
 */
package com.example.autop.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `features`：功能开关（用户覆盖优先）。 */
@Serializable
public data class FeaturesSection(
    @SerialName("subtasks") val subtasks: Boolean = false,
    /** 流程（可重复自动化）总开关，默认关闭（Beta），docs/adr/0030-beta-feature-switch.md。 */
    @SerialName("flows") val flows: Boolean = false,
) : ConfigSection {
    /** 某个 Beta 功能是否开启。 */
    public fun isEnabled(feature: BetaFeature): Boolean =
        when (feature) {
            BetaFeature.FLOWS -> flows
        }
}

/**
 * 设置页“Beta 功能”分组中可开关的功能（docs/adr/0030-beta-feature-switch.md）。
 * 新增一项时同步加配置字段、[FeaturesSection.isEnabled] 分支与设置页开关。
 */
public enum class BetaFeature {
    /** 流程（可重复自动化），对应 `features.flows`。 */
    FLOWS,
}

/** `features` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object FeaturesConfig {
    public val FEATURES: ConfigKey<FeaturesSection> =
        ConfigKey("features", FeaturesSection.serializer()) { FeaturesSection() }

    /** 设置页写入某个 Beta 功能开关时使用的配置路径。 */
    public fun pathOf(feature: BetaFeature): String =
        when (feature) {
            BetaFeature.FLOWS -> "features.flows"
        }

    public val ALL: List<ConfigKey<*>> = listOf(FEATURES)
}
