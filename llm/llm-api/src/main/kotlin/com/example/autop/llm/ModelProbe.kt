/**
 * 测试连接（docs/tech/03 §3）：依次发 3 个最小请求判定 text / tools / vision 可用性。
 */
package com.example.autop.llm

import com.example.autop.core.common.AppError

/**
 * 单项探测结论。工具探测要避免把“支持”误判成“不支持”（误判会让 agent 整条链路降级到
 * JSON 回退协议），所以只有拿到明确反证才给 [UNSUPPORTED]，测不出结论给 [INCONCLUSIVE]。
 */
public enum class ProbeVerdict {
    SUPPORTED,
    UNSUPPORTED,

    /** 未确认：本次没有拿到支持或不支持的证据，调用方保持原有能力设置。 */
    INCONCLUSIVE,
}

/** 单项探测结果。[error] 在 UNSUPPORTED 时是失败原因，在 INCONCLUSIVE 时是未能确认的原因。 */
public data class ProbeResult(
    val verdict: ProbeVerdict,
    val error: AppError? = null,
    val elapsedMs: Long = 0,
) {
    val ok: Boolean get() = verdict == ProbeVerdict.SUPPORTED
}

/** 测试连接报告（设置页据此回填 `ModelCapabilities`）。 */
public data class ProbeReport(
    val text: ProbeResult,
    val tools: ProbeResult,
    val vision: ProbeResult,
)

/** 测试连接端口（llm-api 接口，llm-http 通用实现）。 */
public interface ModelProbe {
    public suspend fun probe(profile: ModelProfile): ProbeReport
}
