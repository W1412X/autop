/**
 * OpenAI 协议的模型列表工厂（N164）：按 [ModelDirectoryFactory.protocolId] 匹配，
 * 禁止按 baseUrl 分支。
 */
package com.example.autop.llm.openai

import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelDirectoryFactory

public class OpenAiModelDirectoryFactory(
    private val context: OpenAiContext,
) : ModelDirectoryFactory {
    override val protocolId: String = PROTOCOL_ID

    override fun create(): ModelDirectory = OpenAiModelDirectory(context)
}
