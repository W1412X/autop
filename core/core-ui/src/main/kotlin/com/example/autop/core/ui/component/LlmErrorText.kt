package com.example.autop.core.ui.component

import androidx.annotation.StringRes
import com.example.autop.core.ui.R
import com.example.autop.llm.LlmErrorCodes

/**
 * 错误码 → 用户可读文案的资源（任务页、流程页、对话页共用）。
 *
 * 只映射 LLM 层的错误码。未知码返回通用提示，不把原始错误码展示给用户。
 */
@StringRes
public fun llmErrorTextRes(code: String): Int =
    when (code) {
        LlmErrorCodes.QUOTA_EXHAUSTED -> R.string.error_llm_quota_exhausted
        LlmErrorCodes.AUTH -> R.string.error_llm_auth
        LlmErrorCodes.RATE_LIMITED -> R.string.error_llm_rate_limited
        LlmErrorCodes.SERVER -> R.string.error_llm_server
        LlmErrorCodes.NETWORK -> R.string.error_llm_network
        LlmErrorCodes.TIMEOUT -> R.string.error_llm_timeout
        LlmErrorCodes.CONTEXT_EXCEEDED -> R.string.error_llm_context_exceeded
        LlmErrorCodes.NOT_CONFIGURED -> R.string.error_llm_not_configured
        else -> R.string.error_generic
    }
