/**
 * EventPayload 持久化编解码（docs/tech/04 §2.1）：`type` 取多态判别字段，
 * 读取时先经 [EventUpcaster] 升级再解码。
 */
package com.example.autop.agent.store

import com.example.autop.agent.EventPayload
import com.example.autop.core.common.AutopJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 载荷编解码。 */
public object PayloadCodec {
    /** 编码结果：[type] 与 [v] 同时入列，便于按 `(type, v)` 升级。 */
    public data class Encoded(
        val type: String,
        val v: Int,
        val json: String,
    )

    public fun encode(payload: EventPayload): Encoded {
        val element = AutopJson.default.encodeToJsonElement(EventPayload.serializer(), payload)
        val type =
            (element as JsonObject)["type"]?.jsonPrimitive?.content
                ?: error("载荷序列化缺少 type 判别字段")
        return Encoded(type = type, v = payload.v, json = element.toString())
    }

    /** 解码：先按 `(type, v)` 升级（docs/tech/04 §2.1 演进规则）。 */
    public fun decode(
        type: String,
        v: Int,
        json: String,
    ): EventPayload = AutopJson.default.decodeFromString(EventPayload.serializer(), EventUpcaster.upcast(type, v, json))
}

/**
 * 事件升级器：payload 只增不改；改结构时 `v` +1 并在[register] 登记
 * `(fromV) -> JsonObject` 变换，读取时逐级升级到当前版本。当前无登记（v1）。
 */
public object EventUpcaster {
    private data class Transform(
        val type: String,
        val fromV: Int,
        val apply: (String) -> String,
    )

    private val transforms = mutableListOf<Transform>()

    /** 登记一条升级变换：把 [type] 从 [fromV] 升到 [fromV] + 1。 */
    public fun register(
        type: String,
        fromV: Int,
        apply: (String) -> String,
    ) {
        transforms += Transform(type, fromV, apply)
    }

    /** 把载荷 JSON 逐级升级到最新；无登记时原样返回。 */
    public fun upcast(
        type: String,
        v: Int,
        json: String,
    ): String {
        var current = json
        var currentV = v
        while (true) {
            val step = transforms.firstOrNull { it.type == type && it.fromV == currentV } ?: break
            current = step.apply(current)
            currentV += 1
        }
        return current
    }

    /** 测试用：清空登记。 */
    public fun clear() {
        transforms.clear()
    }
}
