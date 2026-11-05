/**
 * 进度悬浮窗消息行模型（docs/tech/08 §5.3，N182）：线程事件映射后的展示行，
 * 渲染见 `ProgressRows`，映射见 `ProgressLogic`。
 */
package com.example.autop.feature.overlay.progress

import com.example.autop.agent.PlanItem
import com.example.autop.agent.RunOutcome

/** 进度悬浮窗消息行（N182）。 */
public sealed interface ProgressRow {
    /** 用户消息（右侧气泡；[isSteer] 为运行中补充指令）。 */
    public data class User(
        val text: String,
        val isSteer: Boolean,
    ) : ProgressRow

    /**
     * 助手文本（Markdown 渲染，与对话页 AssistantText 同款）；
     * [streaming] 为流式未落盘增量（纯文本 + 光标，N186 对齐对话页）；
     * [meta] 为 Run 结束元信息行，附着在消息末尾（N183）。
     */
    public data class Assistant(
        val text: String,
        val streaming: Boolean = false,
        val meta: Finished? = null,
    ) : ProgressRow

    /** 思考块（落定折叠；[streaming] 流式时自动展开带光标，N186 对齐对话页）。 */
    public data class Thinking(
        val text: String,
        val streaming: Boolean = false,
    ) : ProgressRow

    /** 工具步骤行（工具类别图标 + 工具 id + 耗时 + 成败状态）。 */
    public data class Tool(
        val toolId: String,
        val ok: Boolean,
        val durationMs: Long,
        val errorText: String?,
    ) : ProgressRow

    /** 计划卡（覆盖式，同对话页 PlanCard）。 */
    public data class Plan(
        val items: List<PlanItem>,
    ) : ProgressRow

    /**
     * Run 结束元信息：任务状态 + 统计一行小字（N183，附着在回复末尾；
     * 末尾非助手文时独立成行）。
     */
    public data class Finished(
        val status: RunOutcome,
        val summary: String?,
        val stepCount: Int,
        val durationMs: Long,
        val outputTokens: Int,
    ) : ProgressRow
}
