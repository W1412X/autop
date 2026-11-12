/**
 * 模型配置（docs/tech/03 §3）：用户可建多份 profile，密钥只存 [ModelProfile.apiKeyRef] 引用。
 */
package com.example.autop.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 模型能力标记（测试连接的工具调用探测回填，默认支持）。
 * [tools] 为 false 时走 JSON 文本回退协议（ADR-0024）：工具清单放进 system，调用以文本形式返回。
 */
@Serializable
public data class ModelCapabilities(
    @SerialName("tools") val tools: Boolean = true,
)

/** 一份模型配置（docs/tech/03 §3）。 */
@Serializable
public data class ModelProfile(
    @SerialName("id") val id: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("protocol") val protocol: String,
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("apiKeyRef") val apiKeyRef: String,
    @SerialName("model") val model: String,
    @SerialName("contextWindow") val contextWindow: Int,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int,
    @SerialName("capabilities") val capabilities: ModelCapabilities = ModelCapabilities(),
    @SerialName("quirks") val quirks: Map<String, String> = emptyMap(),
    @SerialName("extraHeaders") val extraHeaders: Map<String, String> = emptyMap(),
)
