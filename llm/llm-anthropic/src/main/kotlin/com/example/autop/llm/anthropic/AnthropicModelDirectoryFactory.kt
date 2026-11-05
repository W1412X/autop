/**
 * Anthropic 协议的模型列表工厂（N164）：按 [ModelDirectoryFactory.protocolId] 匹配，
 * 禁止按 baseUrl 分支。
 */
package com.example.autop.llm.anthropic

import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelDirectoryFactory

public class AnthropicModelDirectoryFactory(
    private val context: AnthropicContext,
) : ModelDirectoryFactory {
    override val protocolId: String = PROTOCOL_ID

    override fun create(): ModelDirectory = AnthropicModelDirectory(context)
}
