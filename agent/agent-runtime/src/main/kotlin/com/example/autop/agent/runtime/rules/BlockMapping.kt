/**
 * 渲染规则共用的块变换工具（docs/tech/04 §3.4）：
 * 在消息块与 ToolResult 内容两个层面做映射，保持结构不变。
 */
package com.example.autop.agent.runtime.rules

import com.example.autop.llm.Block
import com.example.autop.llm.Msg

/** 对消息的每个块做变换（可一变多）；ToolResult 的内容在块内部映射。 */
internal fun Msg.mapBlocks(transform: (Block) -> List<Block>): Msg =
    copy(
        blocks =
            blocks.flatMap { block ->
                when (block) {
                    is Block.ToolResult -> listOf(block.copy(content = block.content.flatMap(transform)))
                    else -> transform(block)
                }
            },
    )

/** 取消息中第一个 ToolResult。 */
internal fun Msg.toolResult(): Block.ToolResult? = blocks.filterIsInstance<Block.ToolResult>().firstOrNull()

/** 用新内容替换第一个 ToolResult 的内容（其余块不动）。 */
internal fun Msg.withToolResultContent(content: List<Block>): Msg =
    copy(
        blocks =
            blocks.map { block ->
                if (block is Block.ToolResult) block.copy(content = content) else block
            },
    )
