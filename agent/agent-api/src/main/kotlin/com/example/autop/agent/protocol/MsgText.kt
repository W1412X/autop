/**
 * 消息文本提取：回退协议解析与各会话共用。
 */
package com.example.autop.agent.protocol

import com.example.autop.llm.Block
import com.example.autop.llm.Msg

/** 消息中最后一个 Text 块（模型最后的文本）。 */
public fun lastTextOf(msg: Msg): String =
    msg.blocks
        .filterIsInstance<Block.Text>()
        .lastOrNull()
        ?.text
        .orEmpty()
