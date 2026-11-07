/**
 * ask_user 的对话页实现（docs/tech/07 §5）：置 Run 为 WAITING_USER 并等待
 * [RunService.answer] 喂入回答；超时返回 `agent.ask_timeout`。
 * 通知/悬浮球回答入口在后续 WP 补齐，当前只做对话页内回答。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.RunLineage
import com.example.autop.agent.UserInteraction
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.task.RunStatus
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 用户交互端口实现。 */
public class ChatUserInteraction(
    private val runStore: RunStore,
    private val logger: Logger,
    private val lineage: RunLineage? = null,
) : UserInteraction {
    /** runId → 当前等待中的回答。 */
    private val pending = ConcurrentHashMap<RunId, CompletableDeferred<String>>()

    /**
     * 提问：Run 置 [RunStatus.WAITING_USER]，在 [timeoutMs] 内等待 [answer]；
     * 超时返回 `agent.ask_timeout`（模型收到错误后自行兜底）。
     * [options] 供通知按钮/悬浮球使用，对话页直接展示 [question] 自由回答。
     */
    override suspend fun ask(
        runId: RunId,
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> {
        // 流程子 Run 的提问落到用户看得见的根 Run（docs/design/flows.md §6.9）。
        val runId = lineage?.rootOf(runId) ?: runId
        // 先注册回答入口、再置 WAITING_USER：否则状态一出、回答极早到达会被丢弃（answer_dropped）。
        val deferred = CompletableDeferred<String>()
        pending[runId] = deferred
        runStore.mutate(runId) {
            it.copy(status = RunStatus.WAITING_USER, waitingQuestion = question, waitingOptions = options)
        }
        val answer =
            try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } finally {
                pending.remove(runId)
            }
        runStore.mutate(runId) {
            it.copy(status = RunStatus.RUNNING, waitingQuestion = null, waitingOptions = emptyList())
        }
        return if (answer != null) {
            Outcome.Ok(answer)
        } else {
            logger.w(TAG, "ask_timeout", "runId" to runId.value)
            Outcome.Err(AppError(code = AgentErrorCodes.ASK_TIMEOUT, message = "no user answer within $timeoutMs ms"))
        }
    }

    /** 喂入用户回答（由 `RunService.answer` 调用）；无等待中的提问时记日志丢弃。 */
    public fun answer(
        runId: RunId,
        answer: String,
    ) {
        val deferred = pending[runId]
        if (deferred == null || !deferred.complete(answer)) {
            logger.w(TAG, "answer_dropped", "runId" to runId.value)
        }
    }

    private companion object {
        const val TAG = "ChatUserInteraction"
    }
}
