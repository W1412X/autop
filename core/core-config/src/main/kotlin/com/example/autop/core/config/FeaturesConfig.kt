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
    @SerialName("experienceExtraction") val experienceExtraction: Boolean = false,
    @SerialName("subtasks") val subtasks: Boolean = false,
) : ConfigSection

/** `features` 领域全部配置键的登记（DI 多绑定与测试用）。 */
public object FeaturesConfig {
    public val FEATURES: ConfigKey<FeaturesSection> =
        ConfigKey("features", FeaturesSection.serializer()) { FeaturesSection() }

    public val ALL: List<ConfigKey<*>> = listOf(FEATURES)
}
