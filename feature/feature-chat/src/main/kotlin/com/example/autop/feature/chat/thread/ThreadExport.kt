/**
 * 对话日志导出文本组装（docs/tech/08 §4.2，N175）："导出日志"菜单产出单个文本：
 * 头部信息 + 对话事件 JSON lines（seq/createdAt/runId/payload）+ 应用运行日志
 * （`files/logs/autop*.log`，最旧在前）。由 app 侧写缓存文件并经系统分享发出
 * （[com.example.autop.feature.chat.ChatNavigator.shareExport]）。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.core.common.AutopJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 组装导出文本：事件段每行一个 JSON 对象（旧→新）；日志段原样附在末尾。
 */
internal fun buildThreadExport(
    threadId: String,
    events: List<ThreadEvent>,
    logText: String,
): String =
    buildString {
        appendLine("# Autop 对话日志导出")
        appendLine("# thread=$threadId")
        appendLine("# 事件行格式：{\"seq\":..,\"createdAt\":..,\"runId\":..,\"payload\":{..}}")
        appendLine("== 对话事件（JSON lines，旧→新）==")
        events.forEach { appendLine(eventLine(it)) }
        appendLine("== 应用运行日志（files/logs/autop*.log，旧→新）==")
        append(logText)
    }

/** 单条事件的 JSON 行（WP8.7：结构化构造，不拼字符串；序列化失败属编程错误，让测试暴露）。 */
private fun eventLine(event: ThreadEvent): String =
    buildJsonObject {
        put("seq", event.seq)
        put("createdAt", event.createdAt)
        put("runId", event.runId?.value)
        put("payload", AutopJson.default.encodeToJsonElement(EventPayload.serializer(), event.payload))
    }.toString()
