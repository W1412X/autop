package com.example.autop.core.config

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Logger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 构造合并后的配置快照（docs/tech/02 §2.2 生效配置构造）：
 * `defaults → merge(overlays…)`，然后对每个注册 key 解码 + `validate()`，
 * 失败的段回退 `defaultInstance()` 并记入问题列表。
 */
public fun buildConfigSnapshot(
    defaults: JsonObject,
    keys: List<ConfigKey<*>>,
    overlays: List<JsonObject> = emptyList(),
    logger: Logger? = null,
): ConfigSnapshot {
    var merged = defaults
    for (overlay in overlays) {
        merged = merge(merged, overlay)
    }
    val sections = LinkedHashMap<String, ConfigSection>(keys.size)
    val problems = mutableListOf<String>()
    for (key in keys) {
        sections[key.path] = decodeSection(key, resolvePath(merged, key.path), problems, logger)
    }
    return DefaultConfigSnapshot(merged, defaults, sections, problems)
}

private fun <T : ConfigSection> decodeSection(
    key: ConfigKey<T>,
    node: JsonElement?,
    problems: MutableList<String>,
    logger: Logger?,
): ConfigSection =
    if (node !is JsonObject) {
        reportFallback(key, "missing in config tree", problems, logger)
    } else {
        decodeAndValidate(key, node, problems, logger)
    }

private fun <T : ConfigSection> decodeAndValidate(
    key: ConfigKey<T>,
    node: JsonObject,
    problems: MutableList<String>,
    logger: Logger?,
): ConfigSection {
    val decoded =
        try {
            AutopJson.default.decodeFromJsonElement(key.serializer, node)
        } catch (e: SerializationException) {
            return reportFallback(key, "decode failed: ${e.message}", problems, logger)
        }
    val validation = decoded.validate()
    return if (validation.isNotEmpty()) {
        reportFallback(key, "invalid: ${validation.joinToString("; ")}", problems, logger)
    } else {
        decoded
    }
}

private fun <T : ConfigSection> reportFallback(
    key: ConfigKey<T>,
    reason: String,
    problems: MutableList<String>,
    logger: Logger?,
): T {
    problems += "${key.path}: $reason"
    logger?.w("config", "section_invalid", "path" to key.path, "reason" to reason)
    return key.defaultInstance()
}

/**
 * [ConfigSnapshot] 的不可变实现。
 * 段在构造时解码好，[get] 只做查表；类型按 key.path 与注册的 key 一一对应。
 */
internal class DefaultConfigSnapshot(
    private val merged: JsonObject,
    private val defaults: JsonObject,
    private val sections: Map<String, ConfigSection>,
    private val problemList: List<String>,
) : ConfigSnapshot {
    @Suppress("UNCHECKED_CAST")
    // sections 按各 ConfigKey 的 path 构造，同一 path 的段类型与 key 的 T 一致
    override fun <T : ConfigSection> get(key: ConfigKey<T>): T =
        sections[key.path] as? T ?: key.defaultInstance()

    override fun diffFromDefaults(): JsonObject = diffJson(defaults, merged)

    override fun problems(): List<String> = problemList
}
