package com.example.autop.core.prompt

/** prompt 相关错误码（docs/tech/02 §3.1）。 */
public object PromptErrorCodes {
    /** `vars` 中声明的变量缺失 → 渲染失败。 */
    public const val MISSING_VAR: String = "prompt.missing_var"

    /** 正文出现未声明的变量 → 加载失败。 */
    public const val UNDECLARED_VAR: String = "prompt.undeclared_var"
}
