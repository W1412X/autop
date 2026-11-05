/**
 * 统一模型 → OpenAI chat completions 线格式（docs/tech/03 §5.1 请求表）。
 * 只做格式转换；HTTP、SSE、重试由 llm-http 处理。单图加载失败降级为占位文本
 * （[IMAGE_UNAVAILABLE_NOTE]），不再抬升为整个请求失败（N178：历史里一张坏图
 * 曾让此后每轮都在发送前 llm.bad_request，表现为“发消息后无输出”）。
 */
package com.example.autop.llm.openai

import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.ImageLoader
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LoadedImage
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** 模板 id（docs/tech/09 照抄）：工具结果图片的说明文本。 */
public const val TOOL_IMAGES_NOTE_ID: String = "llm.openai.tool_images_note"

/** 单图加载失败时替换进上下文的占位文本（两协议 mapper 同文，N178）。 */
private const val IMAGE_UNAVAILABLE_NOTE: String = "（图片不可用）"

public class OpenAiRequestMapper(
    private val images: ImageLoader,
    private val prompts: PromptRepository,
) {
    public suspend fun mapChatRequest(
        request: LlmRequest,
        quirks: OpenAiQuirks,
        model: String,
        includeUsage: Boolean,
    ): JsonObject {
        val messages = mapMessages(request, quirks)
        return buildJsonObject {
            put("model", model)
            put("messages", messages)
            if (request.tools.isNotEmpty()) {
                put("tools", mapTools(request.tools))
                val choice: JsonElement =
                    when (val toolChoice = request.toolChoice) {
                        ToolChoice.AUTO -> JsonPrimitive("auto")
                        ToolChoice.NONE -> JsonPrimitive("none")
                        ToolChoice.REQUIRED -> JsonPrimitive("required")
                        is ToolChoice.Named ->
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject { put("name", toolChoice.name) },
                                )
                            }
                    }
                put("tool_choice", choice)
            }
            put(quirks.maxTokensField, request.maxOutputTokens)
            request.temperature?.let { put("temperature", it) }
            put("stream", true)
            if (includeUsage) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
        }
    }

    private suspend fun mapMessages(
        request: LlmRequest,
        quirks: OpenAiQuirks,
    ): JsonArray {
        val out = mutableListOf<JsonElement>()
        if (request.system.isNotEmpty()) out += systemMessage(request.system)
        for (msg in request.messages) {
            when (msg.role) {
                Role.USER -> out += userMessage(msg, quirks)
                Role.ASSISTANT -> out += assistantMessage(msg)
                Role.TOOL -> out += toolMessages(msg, quirks)
            }
        }
        return JsonArray(out)
    }

    private fun systemMessage(system: List<SystemPart>): JsonObject =
        buildJsonObject {
            put("role", "system")
            put("content", system.joinToString("\n\n") { it.text })
        }

    private suspend fun userMessage(
        msg: Msg,
        quirks: OpenAiQuirks,
    ): JsonObject {
        val texts = msg.blocks.filterIsInstance<Block.Text>()
        val images = msg.blocks.filterIsInstance<Block.Image>()
        val content: JsonElement =
            if (texts.size == 1 && images.isEmpty()) {
                JsonPrimitive(texts.single().text)
            } else {
                buildJsonArray {
                    for (text in texts) {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text.text)
                            },
                        )
                    }
                    for (image in images) {
                        val loaded = loadImage(image, quirks.imageMaxEdge)
                        add(
                            loaded?.let { imagePart(it) }
                                ?: buildJsonObject {
                                    put("type", "text")
                                    put("text", IMAGE_UNAVAILABLE_NOTE)
                                },
                        )
                    }
                }
            }
        return buildJsonObject {
            put("role", "user")
            put("content", content)
        }
    }

    private fun assistantMessage(msg: Msg): JsonObject {
        val text = msg.blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }
        val calls = msg.blocks.filterIsInstance<Block.ToolCall>()
        return buildJsonObject {
            put("role", "assistant")
            put("content", if (text.isEmpty()) JsonNull else JsonPrimitive(text))
            if (calls.isNotEmpty()) {
                put("tool_calls", JsonArray(calls.map { toolCallJson(it) }))
            }
        }
    }

    private fun toolCallJson(call: Block.ToolCall): JsonObject =
        buildJsonObject {
            put("id", call.id)
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", call.name)
                    put("arguments", call.argsJson)
                },
            )
        }

    private suspend fun toolMessages(
        msg: Msg,
        quirks: OpenAiQuirks,
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val imageCallIds = mutableListOf<String>()
        val imageParts = mutableListOf<JsonElement>()
        for (result in msg.blocks.filterIsInstance<Block.ToolResult>()) {
            val texts =
                result.content
                    .filterIsInstance<Block.Text>()
                    .map { it.text }
                    .toMutableList()
            var hasImage = false
            var missingImages = 0
            for (image in result.content.filterIsInstance<Block.Image>()) {
                val loaded = loadImage(image, quirks.imageMaxEdge)
                if (loaded != null) {
                    imageParts += imagePart(loaded)
                    hasImage = true
                } else {
                    missingImages += 1
                }
            }
            if (missingImages > 0) texts += IMAGE_UNAVAILABLE_NOTE
            if (hasImage) imageCallIds += result.callId
            out +=
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.callId)
                    put("content", texts.joinToString("\n"))
                }
        }
        if (imageParts.isNotEmpty()) {
            val rendered = prompts.render(TOOL_IMAGES_NOTE_ID, mapOf("callIds" to imageCallIds.joinToString(", ")))
            val note =
                when (rendered) {
                    is Outcome.Ok -> rendered.value.text
                    is Outcome.Err -> throw LlmException(rendered.error)
                }
            out +=
                buildJsonObject {
                    put("role", "user")
                    val content = mutableListOf<JsonElement>()
                    content +=
                        buildJsonObject {
                            put("type", "text")
                            put("text", note)
                        }
                    content += imageParts
                    put("content", JsonArray(content))
                }
        }
        return out
    }

    private fun imagePart(loaded: LoadedImage): JsonObject =
        buildJsonObject {
            put("type", "image_url")
            put(
                "image_url",
                buildJsonObject {
                    put("url", "data:${loaded.mime};base64,${Base64.getEncoder().encodeToString(loaded.bytes)}")
                },
            )
        }

    /** 单图加载：失败返回 null（降级占位，N178）；实现方已留痕。 */
    private suspend fun loadImage(
        image: Block.Image,
        maxEdge: Int,
    ): LoadedImage? = images.load(image.fileRef, image.mime, maxEdge)

    private fun mapTools(tools: List<ToolSchema>): JsonArray =
        JsonArray(
            tools.map { tool ->
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        },
                    )
                }
            },
        )
}
