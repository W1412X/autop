/**
 * 记忆工具共用的输出与参数辅助（docs/tech/06 §3）。
 */
package com.example.autop.memory.impl.tools

import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 失败输出：错误进 [ToolOutput.error]，正文给模型看（含 hint，模型才拿得到纠错建议）。 */
internal fun toolError(
    code: String,
    message: String,
    hint: String? = null,
): ToolOutput = errorOutput(AppError(code = code, message = message, hint = hint))

/** 把 [com.example.autop.core.common.Outcome.Err] 转成失败输出（正文含 hint）。 */
internal fun errorOutput(error: AppError): ToolOutput =
    ToolOutput(
        ok = false,
        content = listOf(Block.Text(visibleTextOf(error.message, error.hint))),
        error = error,
    )

/** 模型可见正文：message + “建议：”行（格式与 `agent.tool.error_result` 一致，N172）。 */
private fun visibleTextOf(
    message: String,
    hint: String?,
): String = if (hint.isNullOrBlank()) message else "$message\n建议：$hint"

/** 成功文本输出。 */
internal fun textOutput(text: String): ToolOutput = ToolOutput(ok = true, content = listOf(Block.Text(text)))

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.content

internal fun JsonObject.intOrNull(name: String): Int? = str(name)?.toIntOrNull()

internal fun JsonObject.strings(name: String): List<String>? =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotEmpty() }
