/**
 * 工具输出辅助（与 device-impl/memory-impl 的 ToolSupport 同构）。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.ToolOutput
import com.example.autop.llm.Block

/** 成功文本输出。 */
internal fun textOutput(text: String): ToolOutput = ToolOutput(ok = true, content = listOf(Block.Text(text)))
