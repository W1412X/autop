/**
 * 测试连接（docs/tech/03 §3）：依次发 3 个最小请求判定 text / tools / vision 可用性。
 */
package com.example.autop.llm

import com.example.autop.core.common.AppError

/** 单项探测结果。 */
public data class ProbeResult(
    val ok: Boolean,
    val error: AppError? = null,
    val elapsedMs: Long = 0,
)

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
