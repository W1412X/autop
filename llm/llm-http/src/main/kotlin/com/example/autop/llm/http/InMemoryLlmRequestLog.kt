/**
 * [LlmRequestLog] 进程内实现（docs/tech/08 §4.6 调试页"查看上次请求"）：
 * 只保留最近一次请求快照，[record] 与 [attachUsage] 线程安全。
 */
package com.example.autop.llm.http

import com.example.autop.llm.LastLlmRequest
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmRequestLog
import com.example.autop.llm.Usage

/** 进程内"最近一次请求"记录（[RecordingLlmProvider] 写入，调试页读取）。 */
public class InMemoryLlmRequestLog : LlmRequestLog {
    @Volatile
    private var snapshot: LastLlmRequest? = null

    override fun record(
        request: LlmRequest,
        modelId: String,
        capturedAtMs: Long,
    ) {
        snapshot = LastLlmRequest(request = request, modelId = modelId, capturedAtMs = capturedAtMs)
    }

    override fun attachUsage(usage: Usage?) {
        val current = snapshot ?: return
        snapshot = current.copy(usage = usage)
    }

    override fun last(): LastLlmRequest? = snapshot
}
