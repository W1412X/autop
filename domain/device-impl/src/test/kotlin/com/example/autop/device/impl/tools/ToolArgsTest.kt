package com.example.autop.device.impl.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** 工具参数宽松取值：模型常把数字/布尔传成字符串（docs/tech/04 §2.3）。 */
class ToolArgsTest {
    @Test
    fun `numeric and boolean strings parse loosely`() {
        val args =
            JsonObject(
                mapOf(
                    "num" to JsonPrimitive("12"),
                    "bad" to JsonPrimitive("x"),
                    "flagTrue" to JsonPrimitive("true"),
                    "flagYes" to JsonPrimitive("YES"),
                    "flagOff" to JsonPrimitive("0"),
                    "blank" to JsonPrimitive(""),
                ),
            )
        assertThat(args.int("num")).isEqualTo(12)
        assertThat(args.int("bad")).isNull()
        assertThat(args.long("num")).isEqualTo(12L)
        assertThat(args.bool("flagTrue")).isTrue()
        assertThat(args.bool("flagYes")).isTrue()
        assertThat(args.bool("flagOff")).isFalse()
        assertThat(args.str("blank")).isNull()
        assertThat(args.str("missing")).isNull()
    }

    @Test
    fun `nested object extraction ignores other types`() {
        val inner = JsonObject(mapOf("x1" to JsonPrimitive("0")))
        val args = JsonObject(mapOf("region" to inner, "text" to JsonPrimitive("hi")))
        assertThat(args.obj("region")).isEqualTo(inner)
        assertThat(args.obj("text")).isNull()
        assertThat(args.str("text")).isEqualTo("hi")
    }
}
