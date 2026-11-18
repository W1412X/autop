package com.example.autop.task

import com.example.autop.core.common.AutopJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * 事件源说明目录（`assets/rules/trigger_sources.json`）：每个源的含义、字段说明与示例值，
 * 以及过滤操作符的中文说明，供模型写条件任务时查阅。
 *
 * 字段名的权威来源是 [TriggerSource.fieldNames]；目录只补充说明，缺失的字段说明为空。
 */
@Serializable
public data class TriggerCatalog(
    val schemaVersion: Int,
    /** 操作符名（如 EQUALS）→ 中文说明。 */
    val operators: Map<String, String> = emptyMap(),
    /** 事件源 id → 说明。 */
    val sources: Map<String, TriggerSourceDoc> = emptyMap(),
) {
    public companion object {
        /** 目录结构版本；结构变化时升高（ADR-0032：不做兼容迁移）。 */
        public const val SCHEMA_VERSION: Int = 1

        /** 解析资产文本；版本不符或格式错误时抛错，避免静默用错说明。 */
        public fun parse(text: String): TriggerCatalog {
            val catalog = AutopJson.default.decodeFromString(serializer<TriggerCatalog>(), text)
            check(catalog.schemaVersion == SCHEMA_VERSION) {
                "trigger_sources.json schemaVersion=${catalog.schemaVersion} 与期望的 $SCHEMA_VERSION 不符"
            }
            return catalog
        }
    }
}

/** 一个事件源的说明。[kind]：`event` 每条事件都会触发；`state` 只在状态变化时触发。 */
@Serializable
public data class TriggerSourceDoc(
    val kind: String,
    val description: String,
    val fields: Map<String, TriggerFieldDoc> = emptyMap(),
)

/** 一个字段的说明与示例值（示例只用于理解写法，不代表真实数据）。 */
@Serializable
public data class TriggerFieldDoc(
    val description: String,
    val example: String = "",
)
