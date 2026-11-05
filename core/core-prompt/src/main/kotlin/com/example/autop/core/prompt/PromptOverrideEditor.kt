package com.example.autop.core.prompt

import com.example.autop.core.common.Outcome

/**
 * 用户覆盖正文的编辑接口（docs/tech/02 §3.2）；头部沿用内置模板。
 *
 * `body` 中出现未声明变量 → `prompt.undeclared_var`；id 不存在是编程错误，抛 [IllegalStateException]。
 */
public interface PromptOverrideEditor {
    /** 设置用户覆盖正文。 */
    public suspend fun setOverride(
        id: String,
        body: String,
    ): Outcome<Unit>

    /** 清除用户覆盖，恢复内置正文。 */
    public suspend fun clearOverride(id: String)
}
