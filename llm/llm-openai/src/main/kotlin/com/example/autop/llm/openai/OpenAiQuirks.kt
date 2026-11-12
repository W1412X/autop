/**
 * OpenAI 兼容服务的 quirks（docs/tech/03 §6）：只按 quirk 键取差异，不按 baseUrl 判断。
 */
package com.example.autop.llm.openai

import com.example.autop.llm.ModelProfile

/** quirk 键（docs/tech/03 §6 照抄）。 */
public object OpenAiQuirkKeys {
    public const val CHAT_PATH: String = "chatPath"
    public const val EMBEDDINGS_PATH: String = "embeddingsPath"
    public const val MODELS_PATH: String = "modelsPath"
    public const val MAX_TOKENS_FIELD: String = "maxTokensField"
    public const val REASONING_FIELD: String = "reasoningField"
    public const val ECHO_REASONING: String = "echoReasoning"
    public const val CONTEXT_EXCEEDED_PATTERNS: String = "contextExceededPatterns"
    public const val CONTEXT_EXCEEDED_STATUS: String = "contextExceededStatus"
    public const val IMAGE_MAX_EDGE: String = "imageMaxEdge"

    /** 流式请求是否带 `stream_options.include_usage`；默认开，个别兼容服务不认该字段时设为 `"false"`。 */
    public const val STREAM_USAGE: String = "streamUsage"
}

/** 未配置 quirk 时的兜底（来自配置 `llm.errors` / `llm.image`）。 */
public data class OpenAiQuirksDefaults(
    val contextExceededPatterns: List<String>,
    val imageMaxEdge: Int,
    val contextExceededStatus: List<Int> = DEFAULT_CONTEXT_EXCEEDED_STATUS,
)

/** quirk 解析后的值（默认值见 docs/tech/03 §6）。 */
public data class OpenAiQuirks(
    val chatPath: String,
    val embeddingsPath: String,
    val maxTokensField: String,
    val reasoningField: String,
    /** 回显思考内容到 [reasoningField]（默认关；WP6.7）。 */
    val echoReasoning: Boolean,
    val contextExceededPatterns: List<String>,
    val imageMaxEdge: Int,
    val contextExceededStatus: List<Int> = DEFAULT_CONTEXT_EXCEEDED_STATUS,
) {
    public companion object {
        public fun of(
            profile: ModelProfile,
            defaults: OpenAiQuirksDefaults,
        ): OpenAiQuirks =
            OpenAiQuirks(
                chatPath = profile.quirks[OpenAiQuirkKeys.CHAT_PATH] ?: DEFAULT_CHAT_PATH,
                embeddingsPath = profile.quirks[OpenAiQuirkKeys.EMBEDDINGS_PATH] ?: DEFAULT_EMBEDDINGS_PATH,
                maxTokensField = profile.quirks[OpenAiQuirkKeys.MAX_TOKENS_FIELD] ?: DEFAULT_MAX_TOKENS_FIELD,
                reasoningField = profile.quirks[OpenAiQuirkKeys.REASONING_FIELD] ?: DEFAULT_REASONING_FIELD,
                echoReasoning = profile.quirks[OpenAiQuirkKeys.ECHO_REASONING] == "true",
                contextExceededPatterns =
                    patterns(
                        profile.quirks[OpenAiQuirkKeys.CONTEXT_EXCEEDED_PATTERNS],
                        defaults.contextExceededPatterns,
                    ),
                imageMaxEdge = profile.quirks[OpenAiQuirkKeys.IMAGE_MAX_EDGE]?.toIntOrNull() ?: defaults.imageMaxEdge,
                contextExceededStatus =
                    statusList(
                        profile.quirks[OpenAiQuirkKeys.CONTEXT_EXCEEDED_STATUS],
                        defaults.contextExceededStatus,
                    ),
            )

        private fun patterns(
            quirk: String?,
            fallback: List<String>,
        ): List<String> =
            quirk
                ?.split('|')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: fallback

        private fun statusList(
            quirk: String?,
            fallback: List<Int>,
        ): List<Int> =
            quirk
                ?.split(',', '|')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: fallback
    }
}

/** `llm.errors.contextExceededStatus` 的默认值（agent-harness-overhaul WP6.2）。 */
public val DEFAULT_CONTEXT_EXCEEDED_STATUS: List<Int> = listOf(400, 413, 422)

private const val DEFAULT_CHAT_PATH = "/chat/completions"
private const val DEFAULT_EMBEDDINGS_PATH = "/embeddings"
private const val DEFAULT_MAX_TOKENS_FIELD = "max_tokens"
private const val DEFAULT_REASONING_FIELD = "reasoning_content"
