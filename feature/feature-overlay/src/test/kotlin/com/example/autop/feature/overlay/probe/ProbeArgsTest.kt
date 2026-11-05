/**
 * 参数组装（N207）：逐键编辑值 → 参数 JSON（空省略、类型出值、坏输入降级），
 * 默认值取示例回填。
 */
package com.example.autop.feature.overlay.probe

import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolParamKind
import com.example.autop.agent.ToolProbeParam
import com.example.autop.agent.ToolProbeTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProbeArgsTest {
    @Test
    fun `empty values are omitted from args json`() {
        val params = listOf(param("a", ToolParamKind.STRING), param("b", ToolParamKind.INTEGER))

        val json = argsJsonOf(params, mapOf("a" to "hi", "b" to ""))

        assertThat(json).isEqualTo("{\"a\":\"hi\"}")
    }

    @Test
    fun `values serialize by declared kind`() {
        val params =
            listOf(
                param("s", ToolParamKind.STRING),
                param("i", ToolParamKind.INTEGER),
                param("n", ToolParamKind.NUMBER),
                param("b", ToolParamKind.BOOLEAN),
            )

        val json = argsJsonOf(params, mapOf("s" to "5", "i" to "5", "n" to "1.5", "b" to "true"))

        assertThat(json).isEqualTo("{\"s\":\"5\",\"i\":5,\"n\":1.5,\"b\":true}")
    }

    @Test
    fun `bad number keeps raw text as string`() {
        val params = listOf(param("i", ToolParamKind.INTEGER))

        val json = argsJsonOf(params, mapOf("i" to "abc"))

        assertThat(json).isEqualTo("{\"i\":\"abc\"}")
    }

    @Test
    fun `object value parses as json element and bad json degrades to string`() {
        val params = listOf(param("o", ToolParamKind.OBJECT))

        val good = argsJsonOf(params, mapOf("o" to "{\"k\":1}"))
        val bad = argsJsonOf(params, mapOf("o" to "{ broken"))

        assertThat(good).isEqualTo("{\"o\":{\"k\":1}}")
        assertThat(bad).isEqualTo("{\"o\":\"{ broken\"}")
    }

    @Test
    fun `default values come from examples`() {
        val target =
            ToolProbeTarget(
                name = "t",
                access = ToolAccess.READ_SYSTEM,
                defaultArgs = "{}",
                actsOnScreen = false,
                params = listOf(param("a", ToolParamKind.STRING, example = "示例")),
            )

        assertThat(defaultValuesOf(target)).isEqualTo(mapOf("a" to "示例"))
    }

    private fun param(
        name: String,
        kind: ToolParamKind,
        example: String = "",
    ): ToolProbeParam =
        ToolProbeParam(
            name = name,
            kind = kind,
            required = false,
            enumValues = emptyList(),
            description = "",
            example = example,
        )
}
