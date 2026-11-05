/**
 * Anthropic 兼容服务的 quirks（docs/tech/03 §6）：只按 quirk 键取差异，不按 baseUrl 判断。
 */
package com.example.autop.llm.anthropic

import com.example.autop.llm.ModelProfile

/** quirk 键（docs/tech/03 §6 照抄）。 */
public object AnthropicQuirkKeys {
    public const val MESSAGES_PATH: String = "messagesPath"
    public const val MODELS_PATH: String = "modelsPath"
    public const val TOOL_CHOICE_NONE: String = "toolChoiceNone"
    public const val CONTEXT_EXCEEDED_PATTERNS: String = "contextExceededPatterns"
    public const val IMAGE_MAX_EDGE: String = "imageMaxEdge"
}

/** `toolChoiceNone` quirk 的取值（docs/tech/03 §5.2）。 */
public enum class ToolChoiceNoneMode {
    NATIVE,
    OMIT_TOOLS,
}

/** 未配置 quirk 时的兜底（配置 `llm.errors` / `llm.image` / `llm.anthropic`）。 */
public data class AnthropicQuirksDefaults(
    val contextExceededPatterns: List<String>,
    val imageMaxEdge: Int,
    val apiVersion: String,
)

/** quirk 解析后的值（默认值见 docs/tech/03 §6）。 */
public data class AnthropicQuirks(
    val messagesPath: String,
    val toolChoiceNone: ToolChoiceNoneMode,
    val contextExceededPatterns: List<String>,
    val imageMaxEdge: Int,
) {
    public companion object {
        public fun of(
            profile: ModelProfile,
            defaults: AnthropicQuirksDefaults,
        ): AnthropicQuirks =
            AnthropicQuirks(
                messagesPath = profile.quirks[AnthropicQuirkKeys.MESSAGES_PATH] ?: DEFAULT_MESSAGES_PATH,
                toolChoiceNone = mode(profile.quirks[AnthropicQuirkKeys.TOOL_CHOICE_NONE]),
                contextExceededPatterns =
                    patterns(
                        profile.quirks[AnthropicQuirkKeys.CONTEXT_EXCEEDED_PATTERNS],
                        defaults.contextExceededPatterns,
                    ),
                imageMaxEdge =
                    profile.quirks[AnthropicQuirkKeys.IMAGE_MAX_EDGE]?.toIntOrNull() ?: defaults.imageMaxEdge,
            )

        private fun mode(raw: String?): ToolChoiceNoneMode =
            if (raw == OMIT_TOOLS_VALUE) ToolChoiceNoneMode.OMIT_TOOLS else ToolChoiceNoneMode.NATIVE

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

private const val DEFAULT_MESSAGES_PATH = "/v1/messages"
private const val OMIT_TOOLS_VALUE = "omit_tools"
