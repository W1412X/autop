/**
 * OpenAI 协议工厂（docs/tech/03 §2 扩展点）：按 [LlmProviderFactory.protocolId] 匹配，
 * 禁止按 baseUrl 分支。
 */
package com.example.autop.llm.openai

import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmProviderFactory
import com.example.autop.llm.ModelProfile

/** 协议 id（docs/tech/03 §2 照抄）。 */
public const val PROTOCOL_ID: String = "openai"

public class OpenAiProviderFactory(
    private val context: OpenAiContext,
) : LlmProviderFactory {
    override val protocolId: String = PROTOCOL_ID

    override fun create(profile: ModelProfile): LlmProvider = OpenAiProvider(profile, context)
}
