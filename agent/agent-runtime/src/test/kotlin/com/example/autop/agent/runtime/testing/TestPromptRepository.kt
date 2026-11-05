/**
 * 测试用 [PromptRepository]：真实内置模板（autop.promptDir）+ 合成的 `test.*` 模板
 * （FakeTool 的描述模板，带固定 `param.*` 说明）。
 */
package com.example.autop.agent.runtime.testing

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.prompt.parsePromptTemplate
import com.example.autop.core.prompt.renderPrompt
import com.example.autop.core.testing.FakePromptRepository

/** 真实模板 + 合成测试模板。 */
internal class TestPromptRepository(
    private val base: PromptRepository = FakePromptRepository(),
) : PromptRepository {
    private val extras: Map<String, PromptTemplate> = listOf(FAKE_TOOL_TEMPLATE).associateBy { it.id }

    override fun get(id: String): PromptTemplate = extras[id] ?: base.get(id)

    override fun render(
        id: String,
        vars: Map<String, String>,
    ): Outcome<RenderedPrompt> = renderPrompt(get(id), vars)

    override fun all(): List<PromptTemplate> = base.all() + extras.values.sortedBy { it.id }

    private companion object {
        /** FakeTool 的描述模板：覆盖常见参数名的 `param.*` 说明。 */
        val FAKE_TOOL_TEMPLATE: PromptTemplate =
            parsePromptTemplate(
                """
                ---
                id: test.fake_tool
                version: 1
                vars: []
                meta:
                  param.msg: 测试参数 msg
                  param.id: 测试参数 id
                  param.target: 测试参数 target
                  param.value: 测试参数 value
                  param.text: 测试参数 text
                ---
                测试工具：仅供单测使用的占位工具描述。
                """.trimIndent(),
                PromptSource.BUILTIN,
            )
    }
}
