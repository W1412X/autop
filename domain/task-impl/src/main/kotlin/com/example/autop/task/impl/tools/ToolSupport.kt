/**
 * 工具输出辅助（与 device-impl/memory-impl 的 ToolSupport 同构）。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.llm.Block

/** 成功文本输出。 */
internal fun textOutput(text: String): ToolOutput = ToolOutput(ok = true, content = listOf(Block.Text(text)))

/** 失败输出（code + message + 可选 hint）。 */
internal fun failure(
    code: String,
    message: String,
    hint: String? = null,
): ToolOutput =
    ToolOutput(
        ok = false,
        content = listOf(Block.Text(message)),
        error = AppError(code = code, message = message, hint = hint),
    )
