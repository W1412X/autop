/**
 * 任务 AI 创作会话（对标流程创作 FlowAuthor）：多轮对话 + 带工具的 agent
 * （记忆检索 / 流程清单 / 看屏 / 提问）产出 [TaskDraft] 草稿卡，确认后装配为 [Task]。
 * 不固化步骤——任务以"目标 + 描述"存在，创作经验进提示。
 */
package com.example.autop.task

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.flow.AuthorLive
import com.example.autop.flow.AuthorMessage
import kotlinx.coroutines.flow.StateFlow

/** 任务草稿（AI 创作产物；[save] 时装配为 [Task]）。 */
public data class TaskDraft(
    /** 任务名。 */
    val name: String,
    /** 自然语言目标（含对话中沉淀的提示）。 */
    val goal: String,
    /** 触发类型：manual / once / cron / condition。 */
    val triggerType: String = "manual",
    /** once：ISO-8601 或 `yyyy-MM-dd HH:mm`；cron/condition 时为 null。 */
    val at: String? = null,
    /** cron：5 段表达式。 */
    val cron: String? = null,
    /** cron 时区（null = 系统时区）。 */
    val timezone: String? = null,
    /** condition：事件源 id（如 trigger.notification）。 */
    val source: String? = null,
    /** condition：LLM 判定条件（自然语言）。 */
    val condition: String? = null,
    /** 执行器绑定的流程名（null = 智能体按目标执行）。 */
    val executorFlow: String? = null,
    /** 是否需要屏幕可用（默认 true）。 */
    val needsScreen: Boolean = true,
    /** 补充提示（经验性说明，随 goal 一起交付）。 */
    val hint: String = "",
)

/** 任务创作会话状态。 */
public data class TaskAuthorState(
    val messages: List<AuthorMessage> = emptyList(),
    val draft: TaskDraft? = null,
    val busy: Boolean = false,
    /** 当前轮流式内容；轮结束后为 null。 */
    val live: AuthorLive? = null,
    /** 等待用户回答的问题。 */
    val pendingQuestion: AuthorMessage.Question? = null,
    val errorCode: String? = null,
    /** 正在修改已有任务（保存时更新该任务而不是新建）。 */
    val editing: Boolean = false,
)

/** 一个任务创作会话。 */
public interface TaskAuthorSession {
    public val state: StateFlow<TaskAuthorState>

    /** 发送一条用户消息并驱动一轮（或多轮工具调用）创作。 */
    public suspend fun send(text: String)

    /** 回答 [TaskAuthorState.pendingQuestion]。 */
    public fun answer(text: String)

    /** 取消本轮（挂起的提问/工具调用立即收尾）。 */
    public fun cancel()

    /** 确认草稿：新建任务，或更新 [TaskAuthorState.editing] 对应的既有任务。 */
    public suspend fun save(): Outcome<Task>
}

/** 任务创作入口。 */
public interface TaskAuthor {
    /** 打开一个新会话；[taskId] 非空时基于该任务修改。 */
    public suspend fun open(taskId: TaskId? = null): TaskAuthorSession
}
