/**
 * SSE 解析器（docs/tech/03 §4.2）：逐行读取，空行分隔事件；`[DONE]` 由 OpenAI
 * 适配器处理，这里不特殊对待。
 */
package com.example.autop.llm.http

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.BufferedSource

/** 一条 SSE 事件（[event] 可缺省，多行 data 用 `\n` 连接）。 */
public data class SseEvent(
    val event: String?,
    val data: String,
)

/**
 * 把 SSE 字节流解析为事件流（docs/tech/03 §4.2 规则 1–7）：
 * 空行发出事件；`:` 开头为注释忽略；`event:`/`data:` 记录（去前导一个空格）；
 * 其他字段忽略；流结束有未发数据则补发。阻塞读取，调用方用 `flowOn` 指定线程。
 */
public fun BufferedSource.sseEvents(): Flow<SseEvent> =
    flow {
        var event: String? = null
        val dataLines = mutableListOf<String>()
        while (true) {
            val line = readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (dataLines.isNotEmpty()) {
                        emit(SseEvent(event, dataLines.joinToString("\n")))
                        event = null
                        dataLines.clear()
                    }
                }
                line.startsWith(COMMENT_PREFIX) -> Unit
                line.startsWith(EVENT_PREFIX) -> event = line.removePrefix(EVENT_PREFIX).removePrefix(" ")
                line.startsWith(DATA_PREFIX) -> dataLines += line.removePrefix(DATA_PREFIX).removePrefix(" ")
                else -> Unit
            }
        }
        if (dataLines.isNotEmpty()) {
            emit(SseEvent(event, dataLines.joinToString("\n")))
        }
    }

private const val COMMENT_PREFIX = ":"
private const val EVENT_PREFIX = "event:"
private const val DATA_PREFIX = "data:"
