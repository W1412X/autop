/**
 * 流程领域的服务端口（docs/design/flows.md §12–§13）：校验、导入导出、接口测试。
 * 实现位于 flow-impl，界面（feature-flows）只依赖这里的端口。
 */
package com.example.autop.flow

import com.example.autop.core.common.Outcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 校验结果严重级别。 */
public enum class IssueSeverity {
    ERROR,
    WARNING,
}

/** 一条校验问题；[nodeId] 为 null 表示流程级。 */
public data class FlowIssue(
    val severity: IssueSeverity,
    val nodeId: String?,
    val code: String,
    val message: String,
)

/** 流程校验与分析。 */
public interface FlowValidationService {
    /** 校验；含 [IssueSeverity.ERROR] 的流程不得保存。 */
    public suspend fun validate(definition: FlowDefinition): List<FlowIssue>

    /** 流程是否需要屏幕（决定是否取屏幕租约，§9.4）。 */
    public suspend fun needsScreen(definition: FlowDefinition): Boolean
}

/** 导出包里的接口登记（不含密钥）。 */
@Serializable
public data class BundleEndpoint(
    @SerialName("name") val name: String,
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
)

/** 导入导出包（§13.3）。 */
@Serializable
public data class FlowBundle(
    @SerialName("format") val format: String = FlowSpec.BUNDLE_FORMAT,
    @SerialName("version") val version: Int = FlowSpec.BUNDLE_VERSION,
    @SerialName("flows") val flows: List<FlowDefinition> = emptyList(),
    @SerialName("endpoints") val endpoints: List<BundleEndpoint> = emptyList(),
)

/** 导入时遇到同名/同 id 流程的处理。 */
public enum class ImportConflict {
    /** 覆盖已有流程。 */
    REPLACE,

    /** 另存为新流程（新 id、名称加后缀）。 */
    KEEP_BOTH,

    /** 跳过。 */
    SKIP,
}

/** 导入结果。 */
public data class ImportReport(
    val imported: List<String>,
    val skipped: List<String>,
    /** 引用了但尚未登记的接口名，需要用户补登记（填密钥）。 */
    val missingEndpoints: List<BundleEndpoint>,
    val issues: List<FlowIssue>,
)

/** 流程导入导出。 */
public interface FlowBundleService {
    /** 导出指定流程（及其引用的接口登记，不含密钥）为 JSON 文本。 */
    public suspend fun export(flowIds: List<String>): Outcome<String>

    /** 解析 JSON 文本为包；不写库。 */
    public fun parse(text: String): Outcome<FlowBundle>

    /** 导入：校验后写库，未登记的接口在 [ImportReport.missingEndpoints] 里返回。 */
    public suspend fun import(
        bundle: FlowBundle,
        onConflict: ImportConflict,
    ): ImportReport
}

/** 接口测试结果。 */
public data class EndpointTestResult(
    val ok: Boolean,
    val status: Int?,
    val durationMs: Long,
    val message: String?,
)

/** 接口登记页的“测试”。 */
public interface EndpointTester {
    public suspend fun test(
        name: String,
        path: String,
    ): EndpointTestResult
}
