/**
 * Task JSON 的版本迁移（docs/tech/07 §3）：`schemaVersion` 每 +1 注册一个迁移函数，
 * 目标是最新版本；无法迁移返回 null（该行跳过，不阻塞列表）。
 */
package com.example.autop.task.impl.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Task JSON 迁移。 */
public object TaskJsonMigrations {
    /** 当前 Task JSON 结构版本。 */
    public const val CURRENT_VERSION: Int = 1

    /** 读取 JSON 中的 schemaVersion（缺失视为 1）。 */
    public fun readVersion(
        json: Json,
        raw: String,
    ): Int =
        runCatching {
            json
                .parseToJsonElement(raw)
                .jsonObject[KEY_SCHEMA_VERSION]
                ?.jsonPrimitive
                ?.content
                ?.toInt()
        }.getOrNull() ?: 1

    /**
     * 把 [raw] 从 [fromVersion] 迁到 [CURRENT_VERSION]；返回最新 JSON。
     * 无迁移可走时原样返回；版本来自 JSON 自身（[fromVersion] 仅兜底）。
     */
    public fun migrate(
        raw: String,
        fromVersion: Int,
        json: Json,
    ): String? {
        val version = readVersion(json, raw).coerceAtLeast(fromVersion.coerceAtMost(CURRENT_VERSION))
        var current: String? = raw
        var v = version
        while (current != null && v < CURRENT_VERSION) {
            val step = MIGRATIONS[v]
            current = step?.invoke(current)
            v++
        }
        return current
    }

    /** 版本 n → n+1 的迁移（当前只有 v1 基线，无历史版本）。 */
    private val MIGRATIONS: Map<Int, (String) -> String?> = emptyMap()

    private const val KEY_SCHEMA_VERSION = "schemaVersion"

    /** 给缺失 schemaVersion 的 JSON 补上当前版本（写入前统一规整）。 */
    public fun putSchemaVersion(
        raw: String,
        json: Json,
    ): String {
        val obj = json.parseToJsonElement(raw).jsonObject
        if (KEY_SCHEMA_VERSION in obj) return raw
        val upgraded = JsonObject(obj + (KEY_SCHEMA_VERSION to json.parseToJsonElement("$CURRENT_VERSION")))
        return upgraded.toString()
    }
}
