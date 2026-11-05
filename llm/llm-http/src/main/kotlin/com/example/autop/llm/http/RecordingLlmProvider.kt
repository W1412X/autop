/**
 * 记录采样请求的 [LlmProvider] 装饰（docs/tech/08 §4.6 调试页"查看上次请求"）：
 * 发出时写入请求快照，[LlmStreamEvent.Completed] 时补记用量，其余事件透传。
 */
package com.example.autop.llm.http

import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmRequestLog
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [LlmProvider] 装饰器：把请求与用量记入 [log]。 */
internal class RecordingLlmProvider(
    private val delegate: LlmProvider,
    private val log: LlmRequestLog,
) : LlmProvider {
    override val profile: ModelProfile
        get() = delegate.profile

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            log.record(request = request, modelId = delegate.profile.id, capturedAtMs = System.currentTimeMillis())
            delegate.stream(request).collect { event ->
                if (event is LlmStreamEvent.Completed) {
                    log.attachUsage(event.usage)
                }
                emit(event)
            }
        }
}
