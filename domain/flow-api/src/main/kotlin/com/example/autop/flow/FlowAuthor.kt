/**
 * AI 创作流程（docs/design/flows.md §12）：对话式生成与修改，保存前完整校验。
 */
package com.example.autop.flow

import com.example.autop.core.common.Outcome
import kotlinx.coroutines.flow.StateFlow

/** 创作对话里的一条展示消息。 */
public sealed interface AuthorMessage {
    public data class User(
        val text: String,
    ) : AuthorMessage

    public data class Assistant(
        val text: String,
    ) : AuthorMessage

    /** AI 的提问（带选项）；回答后变为 [User] 消息。 */
    public data class Question(
        val question: String,
        val options: List<String>,
    ) : AuthorMessage

    /** AI 的思考过程（落定后折叠显示，与对话页一致）。 */
    public data class Thinking(
        val text: String,
    ) : AuthorMessage

    /** AI 在后台做的事（观察屏幕、示范、校验），只作提示。 */
    public data class Activity(
        val text: String,
    ) : AuthorMessage
}

/** 正在流式生成的这一轮（思考、正文、正在调用的工具）。 */
public data class AuthorLive(
    val thinking: String = "",
    val text: String = "",
    val tool: String? = null,
)

/** 创作会话状态。 */
public data class AuthorState(
    val messages: List<AuthorMessage> = emptyList(),
    val draft: FlowDefinition? = null,
    val issues: List<FlowIssue> = emptyList(),
    val busy: Boolean = false,
    /** 当前轮的流式内容；轮结束后为 null。 */
    val live: AuthorLive? = null,
    /** 等待用户回答的问题（与最后一条 [AuthorMessage.Question] 对应）。 */
    val pendingQuestion: AuthorMessage.Question? = null,
    /** 示范前等待用户确认；非空时界面显示确认条。 */
    val pendingDemonstration: String? = null,
    val errorCode: String? = null,
)

/** 草稿列表项。 */
public data class DraftInfo(
    val draftId: String,
    val flowId: String?,
    val title: String,
    val updatedAt: Long,
)

/** 一个创作会话。 */
public interface FlowAuthorSession {
    public val draftId: String

    public val state: StateFlow<AuthorState>

    /** 用户发送一条消息，驱动 AI 继续；返回时本轮结束。 */
    public suspend fun send(text: String)

    /** 回答 AI 的提问（[AuthorMessage.Question]）。 */
    public fun answer(text: String)

    /** 用户确认或拒绝 AI 的示范请求。 */
    public fun confirmDemonstration(allowed: Boolean)

    /** 用户手动改了草稿（高级模式编辑后回写，之后 AI 基于新草稿继续）。 */
    public suspend fun replaceDraft(definition: FlowDefinition)

    /** 中止当前一轮。 */
    public fun cancel()

    /** 校验并保存草稿到流程库（有错误返回失败）。 */
    public suspend fun save(): Outcome<StoredFlow>
}

/** 创作入口。 */
public interface FlowAuthor {
    /** 新建会话；[editFlowId] 非空时基于该流程修改；[draftId] 非空时恢复草稿。 */
    public suspend fun open(
        draftId: String? = null,
        editFlowId: String? = null,
    ): FlowAuthorSession

    public suspend fun drafts(): List<DraftInfo>

    public suspend fun deleteDraft(draftId: String)
}
