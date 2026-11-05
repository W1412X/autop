/**
 * 模型列表响应解析（N164）：OpenAI 兼容与 Anthropic 的列表接口响应均为 `data[].id`。
 */
package com.example.autop.llm.http

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.llm.LlmErrorCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 解析 `{"data":[{"id":"..."}]}` → id 列表；无法解析返回 [LlmErrorCodes.BAD_RESPONSE]。 */
public fun parseModelIds(body: String): Outcome<List<String>> {
    val ids =
        runCatching {
            Json
                .parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull()
    return if (ids == null) {
        Outcome.Err(AppError(code = LlmErrorCodes.BAD_RESPONSE, message = "unparseable models response"))
    } else {
        Outcome.Ok(ids)
    }
}
