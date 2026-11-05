/**
 * 模型配置（docs/tech/03 §3）：用户可建多份 profile，密钥只存 [ModelProfile.apiKeyRef] 引用。
 */
package com.example.autop.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 模型能力标记（测试连接后由设置页回填）。 */
@Serializable
public data class ModelCapabilities(
    @SerialName("tools") val tools: Boolean = true,
    @SerialName("vision") val vision: Boolean = false,
    @SerialName("thinking") val thinking: Boolean = false,
    @SerialName("streamUsage") val streamUsage: Boolean = true,
)

/** R1/A5：模型输出坐标的参考空间（docs/adr/0006）。 */
@Serializable
public enum class CoordSpace {
    /** 0–1000 归一化坐标（如 Gemma、Gemini Flash 系）。 */
    NORMALIZED_1000,

    /** 按“看到的图片”的像素坐标回答。 */
    IMAGE_PIXELS,
}

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
    @SerialName("coordSpace") val coordSpace: CoordSpace = CoordSpace.NORMALIZED_1000,
    @SerialName("quirks") val quirks: Map<String, String> = emptyMap(),
    @SerialName("extraHeaders") val extraHeaders: Map<String, String> = emptyMap(),
)
