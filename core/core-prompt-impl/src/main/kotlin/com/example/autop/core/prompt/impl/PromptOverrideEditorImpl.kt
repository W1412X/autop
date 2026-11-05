package com.example.autop.core.prompt.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptOverrideEditor
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.validatePromptBody

/** [PromptOverrideEditor] 实现：覆盖正文须通过变量与结构校验，否则拒绝写入。 */
public class PromptOverrideEditorImpl(
    private val builtins: Map<String, PromptTemplate>,
    private val overrides: PromptOverridesStore,
) : PromptOverrideEditor {
    override suspend fun setOverride(
        id: String,
        body: String,
    ): Outcome<Unit> {
        val builtin = builtins[id] ?: error("unknown prompt id '$id'")
        val valid = validatePromptBody(body, builtin)
        if (valid is Outcome.Err) return valid
        overrides.set(id, body)
        return Outcome.Ok(Unit)
    }

    override suspend fun clearOverride(id: String) {
        overrides.remove(id)
    }
}
