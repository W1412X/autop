package com.example.autop.flow

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 毫秒区间。JSON 中可写成单个数字（固定值）或 `{min,max}`（区间内随机）。
 * min == max 时序列化为数字。
 */
@Serializable(with = DelayRangeSerializer::class)
public data class DelayRange(
    val minMs: Long,
    val maxMs: Long,
) {
    public companion object {
        /** 固定毫秒值。 */
        public fun fixed(ms: Long): DelayRange = DelayRange(ms, ms)
    }
}

/** [DelayRange] 的 JSON 序列化：数字或 `{min,max}` 对象。 */
public object DelayRangeSerializer : KSerializer<DelayRange> {
    private const val KEY_MIN = "min"
    private const val KEY_MAX = "max"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DelayRange")

    override fun serialize(
        encoder: Encoder,
        value: DelayRange,
    ) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("DelayRange 仅支持 JSON")
        if (value.minMs == value.maxMs) {
            json.encodeJsonElement(JsonPrimitive(value.minMs))
        } else {
            json.encodeJsonElement(
                buildJsonObject {
                    put(KEY_MIN, value.minMs)
                    put(KEY_MAX, value.maxMs)
                },
            )
        }
    }

    override fun deserialize(decoder: Decoder): DelayRange {
        val json = decoder as? JsonDecoder ?: throw SerializationException("DelayRange 仅支持 JSON")
        return when (val element = json.decodeJsonElement()) {
            is JsonObject -> {
                val lo = element[KEY_MIN]?.jsonPrimitive?.longOrNull
                val hi = element[KEY_MAX]?.jsonPrimitive?.longOrNull
                if (lo == null || hi == null) throw SerializationException("DelayRange 需要 min 与 max")
                DelayRange(minOf(lo, hi), maxOf(lo, hi))
            }
            is JsonPrimitive -> {
                val v =
                    element.longOrNull ?: element.contentOrNull?.toLongOrNull()
                        ?: throw SerializationException("DelayRange 需要数字")
                DelayRange.fixed(v)
            }
            else -> throw SerializationException("DelayRange 需要数字或 {min,max}")
        }
    }
}
