package com.example.autop.core.prompt.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.RenderedPrompt
import com.example.autop.core.prompt.renderPrompt

/**
 * [PromptRepository] 实现：内置模板 + 用户覆盖正文（头部沿用内置，docs/tech/02 §3.2）。
 *
 * 覆盖正文在写入侧（[PromptOverrideEditorImpl]）已校验，这里直接采用。
 */
public class PromptRepositoryImpl(
    private val builtins: Map<String, PromptTemplate>,
    private val overrides: PromptOverridesStore,
) : PromptRepository {
    override fun get(id: String): PromptTemplate {
        val builtin = builtin(id)
        val body = overrides.current()[id] ?: return builtin
        return builtin.copy(body = body, source = PromptSource.USER)
    }

    override fun render(
        id: String,
        vars: Map<String, String>,
    ): Outcome<RenderedPrompt> = renderPrompt(get(id), vars)

    override fun all(): List<PromptTemplate> = builtins.keys.sorted().map { get(it) }

    private fun builtin(id: String): PromptTemplate = builtins[id] ?: error("unknown prompt id '$id'")
}
