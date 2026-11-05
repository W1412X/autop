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
    public const val CONTEXT_EXCEEDED_PATTERNS: String = "contextExceededPatterns"
    public const val IMAGE_MAX_EDGE: String = "imageMaxEdge"
}

/** 未配置 quirk 时的兜底（来自配置 `llm.errors` / `llm.image`）。 */
public data class OpenAiQuirksDefaults(
    val contextExceededPatterns: List<String>,
    val imageMaxEdge: Int,
)

/** quirk 解析后的值（默认值见 docs/tech/03 §6）。 */
public data class OpenAiQuirks(
    val chatPath: String,
    val embeddingsPath: String,
    val maxTokensField: String,
    val reasoningField: String,
    val contextExceededPatterns: List<String>,
    val imageMaxEdge: Int,
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
                contextExceededPatterns =
                    patterns(
                        profile.quirks[OpenAiQuirkKeys.CONTEXT_EXCEEDED_PATTERNS],
                        defaults.contextExceededPatterns,
                    ),
                imageMaxEdge = profile.quirks[OpenAiQuirkKeys.IMAGE_MAX_EDGE]?.toIntOrNull() ?: defaults.imageMaxEdge,
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
    }
}

private const val DEFAULT_CHAT_PATH = "/chat/completions"
private const val DEFAULT_EMBEDDINGS_PATH = "/embeddings"
private const val DEFAULT_MAX_TOKENS_FIELD = "max_tokens"
private const val DEFAULT_REASONING_FIELD = "reasoning_content"
