package com.example.autop.core.platform

import com.example.autop.core.common.AutopJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** `device_quirks.json` 文档（docs/tech/02 §4.3）；顶层带 schemaVersion。 */
@Serializable
public data class DeviceQuirksRules(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("quirks") val quirks: List<DeviceQuirk> = emptyList(),
)

/** 一条厂商适配规则；[id] 仅用于溯源，[values] 的值统一为字符串。 */
@Serializable
public data class DeviceQuirk(
    @SerialName("id") val id: String,
    @SerialName("match") val match: DeviceQuirkMatch = DeviceQuirkMatch(),
    @SerialName("values") val values: Map<String, String> = emptyMap(),
)

/** 匹配条件：厂商（比较时小写化，空 = 全部）与最低 SDK。 */
@Serializable
public data class DeviceQuirkMatch(
    @SerialName("manufacturer") val manufacturer: List<String> = emptyList(),
    @SerialName("minSdk") val minSdk: Int = 0,
)

/**
 * 解析并校验规则文档：schemaVersion 必须为当前版本，id 不得重复。
 * 规则文件是开发者交付物，坏了是启动失败（同 assets 默认值策略）。
 */
public fun parseDeviceQuirksRules(text: String): DeviceQuirksRules {
    val rules =
        try {
            AutopJson.default.decodeFromString(DeviceQuirksRules.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalStateException("device_quirks.json is not valid: ${e.message}", e)
        }
    check(rules.schemaVersion == CURRENT_DEVICE_QUIRKS_SCHEMA_VERSION) {
        "device_quirks.json schemaVersion must be $CURRENT_DEVICE_QUIRKS_SCHEMA_VERSION, got ${rules.schemaVersion}"
    }
    val ids = rules.quirks.map { it.id }
    check(ids.toSet().size == ids.size) { "duplicate device quirk id in device_quirks.json" }
    return rules
}

public const val CURRENT_DEVICE_QUIRKS_SCHEMA_VERSION: Int = 1
