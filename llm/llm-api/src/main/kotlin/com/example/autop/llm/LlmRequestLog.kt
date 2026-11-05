/**
 * 采样请求记录（docs/tech/08 §4.6 调试页"查看上次请求"）：进程内只保留最近一次
 * 采样请求快照，供调试页查看本轮 system 文本与用量（WP10 扩展 live 视图与 token 分布）。
 */
package com.example.autop.llm

/** 最近一次采样请求快照。 */
public data class LastLlmRequest(
    /** 发出的完整请求（system / messages / tools）。 */
    val request: LlmRequest,
    /** 采样所用的模型配置 id。 */
    val modelId: String,
    /** 记录时刻（epoch millis）。 */
    val capturedAtMs: Long,
    /** 流结束时的用量（未跑完为 null）。 */
    val usage: Usage? = null,
)

/** 采样请求记录器（进程内单例，仅保留最近一次）。 */
public interface LlmRequestLog {
    /** 记录一次发出的请求（[capturedAtMs] 为 epoch millis）。 */
    public fun record(
        request: LlmRequest,
        modelId: String,
        capturedAtMs: Long,
    )

    /** 补记最近一次请求的用量（[LlmStreamEvent.Completed.usage]）。 */
    public fun attachUsage(usage: Usage?)

    /** 最近一次请求快照；尚未发出请求时为 null。 */
    public fun last(): LastLlmRequest?
}
