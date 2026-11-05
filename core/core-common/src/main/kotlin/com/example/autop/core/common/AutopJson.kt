package com.example.autop.core.common

import kotlinx.serialization.json.Json

/**
 * 全局统一的 JSON 配置（docs/tech/02 §1.5）。所有模块必须使用这里的实例，
 * 禁止自行 `Json { }`（保证事件、配置、快照的序列化行为一致）。
 */
public object AutopJson {
    /** 默认配置：宽容读取、完整写出，多态用 `type` 判别字段。 */
    public val default: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            classDiscriminator = "type"
        }

    /** 美化输出，用于导出与高级配置编辑（人要读的场合）。 */
    public val pretty: Json =
        Json(from = default) {
            prettyPrint = true
        }
}
