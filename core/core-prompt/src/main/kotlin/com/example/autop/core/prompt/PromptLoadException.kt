package com.example.autop.core.prompt

import com.example.autop.core.common.AppError

/**
 * 模板加载失败（启动失败语义），携带 [AppError]（含 [PromptErrorCodes] 错误码）。
 *
 * 模板是开发者交付物，加载失败应终止启动而不是静默降级（docs/tech/02 §3.1）。
 */
public class PromptLoadException(
    public val error: AppError,
    cause: Throwable? = null,
) : IllegalStateException(error.message, cause)
