/**
 * 统一消息模型（docs/tech/03 §1）：system 不进消息列表（见 [LlmRequest.system]），
 * 消息由 [Msg] 承载、内容拆成 [Block] 列表。
 */
package com.example.autop.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 消息角色（system 单独传，不进消息列表）。 */
@Serializable
public enum class Role {
    USER,
    ASSISTANT,
    TOOL,
}

/** 消息内容块（docs/tech/03 §1）。 */
@Serializable
public sealed interface Block {
    /** 纯文本。 */
    @Serializable
    @SerialName("text")
    public data class Text(
        val text: String,
    ) : Block

    /** 图片只存文件引用（发送时经 ImageLoader 读取并 base64）。
     *  [alt] 为图片说明（docs/tech/05 §12.2：截图附带坐标说明），默认空串兼容旧数据。 */
    @Serializable
    @SerialName("image")
    public data class Image(
        val fileRef: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val alt: String = "",
    ) : Block

    /** 工具调用（仅 ASSISTANT 消息）。 */
    @Serializable
    @SerialName("tool_call")
    public data class ToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
    ) : Block

    /** 工具结果（仅 TOOL 消息，[content] 只能是 [Text] / [Image]）。 */
    @Serializable
    @SerialName("tool_result")
    public data class ToolResult(
        val callId: String,
        val content: List<Block>,
        val isError: Boolean,
    ) : Block

    /** 思考块（开启思考时必须原样回传，docs/tech/03 §5.2）。 */
    @Serializable
    @SerialName("thinking")
    public data class Thinking(
        val text: String,
        val signature: String? = null,
        val redacted: String? = null,
    ) : Block
}

/** 一条消息（docs/tech/03 §1）。 */
@Serializable
public data class Msg(
    val role: Role,
    val blocks: List<Block>,
)
