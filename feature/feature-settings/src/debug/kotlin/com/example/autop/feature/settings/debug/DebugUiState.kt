/**
 * 调试页状态与操作（docs/tech/08 §4.6 me/debug）：
 * 采集统计、截图、工具试执行结果与快照导出。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.llm.Role

/** 一次屏幕采集的统计（docs/tech/08 §4.6：节点数、可交互数、估算 token、耗时）。 */
internal data class DebugStats(
    val nodeCount: Int,
    val interactiveCount: Int,
    val tokens: Int,
    val elapsedMs: Long,
)

/** 工具试执行结果（ToolOutput 的展示视图）。 */
internal data class DebugToolOutcome(
    val toolId: String,
    val ok: Boolean,
    val text: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val control: String? = null,
)

/**
 * "查看上次请求"展示视图（docs/tech/08 §4.6；WP10 扩展 live 视图与 token 分布）：
 * system 全文（核对 L0 ≤ 1500 token）、请求规模、用量、逐条消息摘要与 token 分布。
 */
internal data class DebugRequestView(
    val modelId: String,
    val capturedAt: Long,
    val systemText: String,
    val systemTokens: Int,
    val messageCount: Int,
    val toolCount: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    /** live 视图：逐条消息的角色与摘要文本（WP10）。 */
    val liveMessages: List<DebugLiveMessage> = emptyList(),
    /** token 分布：system/消息/工具/合计估算（WP10）。 */
    val tokenParts: List<DebugTokenPart> = emptyList(),
)

/** live 视图一条消息摘要（WP10）。 */
internal data class DebugLiveMessage(
    val role: Role,
    val text: String,
)

/** token 分布分段（WP10）。 */
internal enum class DebugTokenSection {
    SYSTEM,
    MESSAGES,
    TOOLS,
    TOTAL,
}

/** token 分布一条（WP10）。 */
internal data class DebugTokenPart(
    val section: DebugTokenSection,
    val tokens: Int,
)

/** 调试页可恢复错误。 */
internal sealed interface DebugError {
    /** 屏幕采集 / 截图失败。 */
    data object CaptureFailed : DebugError

    /** 导出快照夹具失败。 */
    data object ExportFailed : DebugError

    /** 参数 JSON 解析失败。 */
    data object ArgsInvalid : DebugError

    /** 未选择工具就执行。 */
    data object ToolMissing : DebugError
}

/** 调试页状态。 */
internal data class DebugUiState(
    /** 倒计时剩余秒数（null = 未在倒计时）。 */
    val countdown: Int? = null,
    val busy: Boolean = false,
    /** 序列化屏幕文本（`tool.observe` 的 full 输出）。 */
    val serializedText: String = "",
    val stats: DebugStats? = null,
    /** 采集时使用的观测模式（`a11y` / `visual`）。 */
    val observeMode: String = "a11y",
    /** 截图的本地文件路径（Block.Image.fileRef）。 */
    val screenshotPath: String? = null,
    val toolIds: List<String> = emptyList(),
    val selectedToolId: String? = null,
    val argsText: String = "",
    val toolOutcome: DebugToolOutcome? = null,
    /** 导出时脱敏文本（docs/tech/05 §11）。 */
    val redact: Boolean = true,
    /** 已导出的文件名（null = 尚未导出成功）。 */
    val exportedFile: String? = null,
    /** 最近一次采样请求快照（"查看上次请求"，docs/tech/08 §4.6）。 */
    val lastRequest: DebugRequestView? = null,
    val showLastRequest: Boolean = false,
    val error: DebugError? = null,
)

/** 调试页用户操作。 */
internal sealed interface DebugAction {
    /** 采集当前屏幕（3 秒后）。 */
    data object Capture : DebugAction

    /** 截图。 */
    data object Screenshot : DebugAction

    /** 切换采集模式（a11y / visual）。 */
    data class SetObserveMode(
        val mode: String,
    ) : DebugAction

    /** 导出快照夹具。 */
    data object Export : DebugAction

    data class SetRedact(
        val value: Boolean,
    ) : DebugAction

    data class SelectTool(
        val toolId: String,
    ) : DebugAction

    data class SetArgs(
        val text: String,
    ) : DebugAction

    /** 执行选中的工具。 */
    data object ExecuteTool : DebugAction

    /** 查看上次请求（docs/tech/08 §4.6）。 */
    data object ShowLastRequest : DebugAction

    /** 收起上次请求面板。 */
    data object HideLastRequest : DebugAction

    data object DismissError : DebugAction
}
