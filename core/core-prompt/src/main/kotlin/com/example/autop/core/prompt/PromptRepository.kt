package com.example.autop.core.prompt

import com.example.autop.core.common.Outcome

/**
 * 模板仓库（docs/tech/02 §3.2）：内置模板 + 用户覆盖正文。
 *
 * 用户覆盖只替换正文，头部（id/version/vars/optionalVars/meta）沿用内置模板。
 */
public interface PromptRepository {
    /** 取模板；id 不存在是编程错误（id 来自 `PromptIds` 常量），抛 [IllegalStateException]。 */
    public fun get(id: String): PromptTemplate

    /** 渲染模板；变量缺失返回 `prompt.missing_var`。 */
    public fun render(
        id: String,
        vars: Map<String, String> = emptyMap(),
    ): Outcome<RenderedPrompt>

    /** 全部模板（含用户覆盖后的版本），按 id 排序。 */
    public fun all(): List<PromptTemplate>
}
