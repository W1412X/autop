/**
 * 工具参数校验（docs/tech/04 §3.3）：形状（object/required/anyOf/additionalProperties）
 * 与取值（string/integer/number/boolean/array 的 enum/范围/长度/items）；
 * 数值/布尔/字符串参数支持宽松转换（"12"/"yes"/数字传 string），null 一律拒绝。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.runtime.tools.ArgsValidator
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class ArgsValidationTest {
    @Test
    fun `整数字符串宽松转换为整数`() {
        val schema = objSchema("n" to buildJsonObject { put("type", "integer") })
        val args = buildJsonObject { put("n", "12") }

        val result = ArgsValidator.validate(schema, args)

        assertThat(result.errors).isEmpty()
        assertThat(
            result.args
                ?.get("n")
                ?.jsonPrimitive
                ?.intOrNull,
        ).isEqualTo(12)
    }

    @Test
    fun `数字传给 string 参数宽松取 content`() {
        val schema = objSchema("id" to buildJsonObject { put("type", "string") })

        val result = ArgsValidator.validate(schema, buildJsonObject { put("id", 3) })

        assertThat(result.errors).isEmpty()
        val id = result.args?.get("id")
        assertThat(id?.jsonPrimitive?.content).isEqualTo("3")
    }

    @Test
    fun `string 的 enum 与长度约束`() {
        val schema =
            objSchema(
                "s" to
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("a")
                                add("b")
                            },
                        )
                        put("minLength", 1)
                        put("maxLength", 2)
                    },
            )

        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("s", "a") }).errors).isEmpty()
        // enum 与长度约束会同时报告多条错误
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("s", "c") }).errors)
            .containsExactly("s.: not one of \"a\", \"b\"")
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("s", "") }).errors)
            .containsExactly("s.: not one of \"a\", \"b\"", "s.: shorter than 1")
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("s", "abc") }).errors)
            .containsExactly("s.: not one of \"a\", \"b\"", "s.: longer than 2")
    }

    @Test
    fun `integer 的范围与类型错误`() {
        val schema =
            objSchema(
                "n" to
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 5)
                        put("maximum", 10)
                    },
            )

        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("n", 7) }).errors).isEmpty()
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("n", 3) }).errors.single())
            .contains("less than")
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("n", 11) }).errors.single())
            .contains("greater than")
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("n", "abc") }).errors)
            .containsExactly("n.: expected integer")
    }

    @Test
    fun `number 与 boolean 类型检查`() {
        val schema =
            objSchema(
                "f" to buildJsonObject { put("type", "number") },
                "b" to buildJsonObject { put("type", "boolean") },
            )

        val ok =
            ArgsValidator.validate(
                schema,
                buildJsonObject {
                    put("f", 1.5)
                    put("b", true)
                },
            )
        assertThat(ok.errors).isEmpty()
        // 与工具层取值对称："yes"/"1" 等宽松布尔也接受（N172）
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("b", "yes") }).errors).isEmpty()
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("f", "x") }).errors)
            .containsExactly("f.: expected number")
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("b", "maybe") }).errors)
            .containsExactly("b.: expected boolean")
    }

    @Test
    fun `array 按 items 递归校验元素`() {
        val schema =
            objSchema(
                "arr" to
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    },
            )

        val ok = ArgsValidator.validate(schema, buildJsonObject { put("arr", buildJsonArray { add("a") }) })
        assertThat(ok.errors).isEmpty()
        // 数字元素宽松转为字符串（与 string 参数对称，N172）
        val coerced =
            ArgsValidator.validate(
                schema,
                buildJsonObject {
                    put("arr", buildJsonArray { add(2) })
                },
            )
        assertThat(coerced.errors).isEmpty()
        val bad =
            ArgsValidator.validate(
                schema,
                buildJsonObject {
                    put(
                        "arr",
                        buildJsonArray {
                            add("a")
                            add(buildJsonObject { put("x", 1) })
                        },
                    )
                },
            )
        assertThat(bad.errors).containsExactly("arr.1.: expected string")
    }

    @Test
    fun `required、null 与 additionalProperties 检查`() {
        val schema =
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("a", buildJsonObject { put("type", "string") }) })
                put("required", buildJsonArray { add("a") })
                put("additionalProperties", false)
            }

        assertThat(ArgsValidator.validate(schema, buildJsonObject { }).errors).containsExactly("a: required")
        // null 值同时触发 required 与 null 拒绝两条错误
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("a", JsonNull) }).errors)
            .containsExactly("a: required", "a.: null not allowed")
        val withExtra =
            ArgsValidator.validate(
                schema,
                buildJsonObject {
                    put("a", "x")
                    put("z", 1)
                },
            )
        assertThat(withExtra.errors).containsExactly("z: unexpected parameter")
        val nullValue =
            ArgsValidator.validate(
                objSchema("a" to buildJsonObject { put("type", "string") }),
                buildJsonObject { put("a", JsonNull) },
            )
        assertThat(nullValue.errors).containsExactly("a.: null not allowed")
    }

    @Test
    fun `oneOf 旧键兼容：多键组须整组满足`() {
        val schema =
            buildJsonObject {
                put("type", "object")
                put(
                    "oneOf",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "required",
                                    buildJsonArray {
                                        add("q")
                                        add("seq_from")
                                    },
                                )
                            },
                        )
                    },
                )
            }

        assertThat(ArgsValidator.validate(schema, buildJsonObject { }).errors)
            .containsExactly("one of [q, seq_from] is required")
        assertThat(ArgsValidator.validate(schema, buildJsonObject { put("q", "关键词") }).errors)
            .containsExactly("one of [q, seq_from] is required")
        val both =
            ArgsValidator.validate(
                schema,
                buildJsonObject {
                    put("q", "关键词")
                    put("seq_from", 1)
                },
            )
        assertThat(both.errors).isEmpty()
    }

    @Test
    fun `anyOf 多组至少满足一组（open_app 二选一）`() {
        val schema =
            buildJsonObject {
                put("type", "object")
                put(
                    "anyOf",
                    buildJsonArray {
                        add(buildJsonObject { put("required", buildJsonArray { add("package") }) })
                        add(buildJsonObject { put("required", buildJsonArray { add("name") }) })
                    },
                )
            }

        val byPkg = ArgsValidator.validate(schema, buildJsonObject { put("package", "com.tencent.mm") })
        assertThat(byPkg.errors).isEmpty()
        val byName = ArgsValidator.validate(schema, buildJsonObject { put("name", "微信") })
        assertThat(byName.errors).isEmpty()
        val both =
            ArgsValidator.validate(
                schema,
                buildJsonObject {
                    put("package", "com.tencent.mm")
                    put("name", "微信")
                },
            )
        assertThat(both.errors).isEmpty()
        assertThat(ArgsValidator.validate(schema, buildJsonObject { }).errors)
            .containsExactly("one of [package, name] is required")
    }

    @Test
    fun `object 形状对非 object 报错`() {
        val errors = mutableListOf<String>()

        ArgsValidator.validateValue(buildJsonObject { put("type", "object") }, JsonPrimitive("x"), "p.", errors)

        assertThat(errors).containsExactly("p.: expected object")
    }

    /** 带 properties 的 object schema；[props] 为属性名到属性 schema。 */
    private fun objSchema(vararg props: Pair<String, kotlinx.serialization.json.JsonObject>) =
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { props.forEach { (name, schema) -> put(name, schema) } })
        }
}
