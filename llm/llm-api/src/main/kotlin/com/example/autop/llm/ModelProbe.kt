/**
 * 测试连接（docs/tech/03 §3）：文本请求判定能否连通；工具调用请求判定模型是否支持工具调用。
 */
package com.example.autop.llm

import com.example.autop.core.common.AppError

/**
 * 单项探测结论。工具探测只有拿到明确反证才给 [UNSUPPORTED]（误判会把可用模型切到文本回退协议），
 * 测不出结论给 [INCONCLUSIVE]。
 */
public enum class ProbeVerdict {
    SUPPORTED,
    UNSUPPORTED,

    /** 未确认：本次没有拿到支持或不支持的证据，调用方保持原有能力设置。 */
    INCONCLUSIVE,
}

/** 单项探测结果。[error] 在 UNSUPPORTED / INCONCLUSIVE 时给出原因，[elapsedMs] 为耗时。 */
public data class ProbeResult(
    val verdict: ProbeVerdict,
    val error: AppError? = null,
    val elapsedMs: Long = 0,
) {
    val ok: Boolean get() = verdict == ProbeVerdict.SUPPORTED
}

/** 测试连接报告。 */
public data class ProbeReport(
    val text: ProbeResult,
    val tools: ProbeResult,
)

/** 测试连接端口（llm-api 接口，llm-http 通用实现）。 */
public interface ModelProbe {
    public suspend fun probe(profile: ModelProfile): ProbeReport
}
