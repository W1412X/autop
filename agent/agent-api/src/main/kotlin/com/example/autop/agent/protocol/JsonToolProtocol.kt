/**
 * JSON 回退协议（docs/tech/04 §3.8）：模型不支持原生 tools 时，
 * 用文本 JSON 表达工具调用；历史里的 ToolCall/ToolResult 降级为 USER 文本。
 *
 * 放在 agent-api 供智能体主循环与流程创作会话共用，保证两条链路的
 * 能力判定与协议格式完全一致。
 */
package com.example.autop.agent.protocol

import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.catchingAsError
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** JSON 协议的解析结果。 */
public sealed interface JsonParsed {
    /** `{"tool_calls":[{"name","arguments"}]}`。 */
    public data class Calls(
        val calls: List<Block.ToolCall>,
    ) : JsonParsed

    /** `{"final":"…"}`。 */
    public data class Final(
        val text: String,
    ) : JsonParsed

    public data class Invalid(
        val error: String,
    ) : JsonParsed
}

/** JSON 回退协议工具。 */
public object JsonToolProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型文本：提取第一个顶层 JSON 对象（容忍前后说明文字与 ``` 包裹），
     * 转为工具调用（id = `fb_<turn>_<i>`）或最终文本。
     */
    public fun parse(
        text: String,
        turn: Int,
    ): JsonParsed {
        val raw = extractJsonObject(text)
        if (raw == null) return JsonParsed.Invalid("no top-level JSON object found")
        val parsed =
            catchingAsError(CommonErrorCodes.SERIALIZATION) {
                json.parseToJsonElement(raw).jsonObject
            }
        val obj = parsed.getOrNull()
        val final = obj?.get("final") as? JsonPrimitive
        return when {
            obj == null -> JsonParsed.Invalid((parsed as? Outcome.Err)?.error?.message.orEmpty())
            obj["tool_calls"] is JsonArray -> JsonParsed.Calls(toCalls(obj["tool_calls"] as JsonArray, turn))
            final != null && final.isString -> JsonParsed.Final(final.content)
            else -> JsonParsed.Invalid("JSON must contain tool_calls or final")
        }
    }

    /**
     * 原生工具模式下的正文兜底：部分服务/代理会把工具调用漏进正文。只在**整段正文**
     * （去掉 ``` 包裹后）恰好是一个含非空 `tool_calls` 数组的 JSON 对象、且所有工具名都在
     * [toolNames] 中时返回调用；正文里举例提到 JSON 的普通回答不会被当成调用。
     */
    public fun parseStrictCalls(
        text: String,
        turn: Int,
        toolNames: Set<String>,
    ): List<Block.ToolCall>? {
        val body = stripFence(text.trim())
        val whole = body.startsWith('{') && scanObject(body, 0) == body
        val array =
            if (whole) {
                runCatching { json.parseToJsonElement(body).jsonObject["tool_calls"] as? JsonArray }.getOrNull()
            } else {
                null
            }
        val calls = array?.takeIf { it.isNotEmpty() }?.let { runCatching { toCalls(it, turn) }.getOrNull() }
        return calls?.takeIf { list -> list.all { it.name in toolNames } }
    }

    /** 去掉整段 ``` / ```json 包裹；没有包裹原样返回。 */
    private fun stripFence(text: String): String {
        if (!text.startsWith(FENCE) || !text.endsWith(FENCE) || text.length < FENCE.length * 2) return text
        return text.removeSuffix(FENCE).substringAfter('\n', "").trim()
    }

    private const val FENCE = "```"

    /** `tool_calls` 数组转工具调用（id = `fb_<turn>_<i>`）。 */
    private fun toCalls(
        calls: JsonArray,
        turn: Int,
    ): List<Block.ToolCall> =
        calls.mapIndexed { i, item ->
            val one = item.jsonObject
            Block.ToolCall(
                id = "fb_${turn}_$i",
                name = one["name"]?.jsonPrimitive?.content.orEmpty(),
                argsJson = one["arguments"]?.toString() ?: "{}",
            )
        }

    /**
     * 历史降级：ToolResult → USER 文本（模板 `agent.fallback.tool_result`）；一条助手消息里的
     * 全部 ToolCall 合成一段协议 JSON `{"tool_calls":[…]}`（与 `agent.fallback.json_protocol`
     * 要求模型输出的格式一致，模型看到的自己的历史就是它该写的样子）。
     */
    public fun downgrade(
        messages: List<Msg>,
        prompts: PromptRepository,
    ): List<Msg> {
        val namesByCallId = mutableMapOf<String, String>()
        for (msg in messages) {
            for (block in msg.blocks) {
                if (block is Block.ToolCall) namesByCallId[block.id] = block.name
            }
        }
        return messages.map { msg ->
            when (msg.role) {
                Role.ASSISTANT -> {
                    val calls = msg.blocks.filterIsInstance<Block.ToolCall>()
                    val others = msg.blocks.filter { it !is Block.ToolCall }
                    if (calls.isEmpty()) msg else Msg(Role.ASSISTANT, others + Block.Text(callsJsonText(calls)))
                }
                Role.TOOL ->
                    Msg(
                        Role.USER,
                        // 每个 ToolResult 一组：结果文字 + 原图片（图片不因降级丢失，WP6.7）。
                        msg.blocks.flatMap { block ->
                            val result = block as? Block.ToolResult ?: return@flatMap listOf(block)
                            val name = namesByCallId[result.callId] ?: result.callId
                            val body = result.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                            val text =
                                prompts
                                    .render("agent.fallback.tool_result", mapOf("name" to name, "result" to body))
                                    .getOrNull()
                                    ?.text
                                    .orEmpty()
                            listOf(Block.Text(text)) + result.content.filterIsInstance<Block.Image>()
                        },
                    )
                Role.USER -> msg
            }
        }
    }

    /** 工具清单文本：每个工具一行 `name(参数: 类型, …): 描述首句`。 */
    public fun toolListText(tools: List<ToolSchema>): String =
        tools.joinToString("\n") { tool ->
            val params =
                tool.parameters["properties"]
                    ?.jsonObject
                    .orEmpty()
                    .entries
                    .joinToString(", ") { (name, schema) ->
                        "$name: ${schema.jsonObject["type"]?.jsonPrimitive?.content ?: "any"}"
                    }
            val firstLine = tool.description.substringBefore("\n").substringBefore("。")
            "${tool.name}($params): $firstLine"
        }

    /** 一组 ToolCall 的协议 JSON 文本 `{"tool_calls":[{"name","arguments"}]}`（历史降级用）。 */
    public fun callsJsonText(calls: List<Block.ToolCall>): String =
        buildJsonObject {
            put(
                "tool_calls",
                buildJsonArray {
                    for (call in calls) {
                        add(
                            buildJsonObject {
                                put("name", call.name)
                                put("arguments", json.parseToJsonElement(call.argsJson.ifEmpty { "{}" }))
                            },
                        )
                    }
                },
            )
        }.toString()

    /** 提取第一个顶层 JSON 对象（跳过 ``` 包裹与前后文字，括号配对且跳过字符串字面量）。 */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        return if (start < 0) null else scanObject(text, start)
    }

    /** 从 [start] 起扫描括号配对；不完整时返回 null。 */
    private fun scanObject(
        text: String,
        start: Int,
    ): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
