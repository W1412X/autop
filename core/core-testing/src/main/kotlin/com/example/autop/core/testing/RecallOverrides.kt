package com.example.autop.core.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 覆盖 `agent.recall.channels`。历史检索默认只启用 `recall.fts`（ADR-0028，向量通道默认关闭），
 * 需要向量通道的测试用它显式启用。
 */
public fun recallChannelsOverride(vararg channels: String): JsonObject =
    buildJsonObject {
        putJsonObject("agent") {
            putJsonObject("recall") {
                putJsonArray("channels") {
                    channels.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    }
