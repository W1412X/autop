/**
 * Run 排队（docs/tech/07 §4.3）：创建 RunRecord 入队（对话沿用线程、任务每次
 * 新建线程）；门条件检查在 [RunScheduler]，执行在 [RunRunner]。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentSession
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.flatMap
import com.example.autop.core.common.getOrNull
import com.example.autop.device.ScreenReader
import com.example.autop.llm.Block
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Run 排队（供 DI 装配）。 */
@Suppress("LongParameterList") // 端口装配面大是执行器职责的自然结果（DI 注入）
public class RunExecutor(
    private val agentSession: AgentSession,
    private val threadStore: ThreadStore,
    private val runStore: RunStore,
    private val pending: PendingRuns,
    private val scheduler: RunScheduler,
    private val screenReader: ScreenReader,
    private val titleGenerator: ChatTitleGenerator,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val logger: Logger,
    private val scope: CoroutineScope,
) {
    /**
     * 发起对话（[threadId] 为 null 时新建线程）：首条消息截断做占位标题（列表立刻可读），
     * 入队成功后异步用 LLM 精炼，仅当标题仍是占位值才覆盖。
     */
    suspend fun launchChat(
        threadId: ThreadId?,
        text: String,
        attachCurrentScreen: Boolean,
    ): Outcome<RunRecord> {
        val placeholder = if (threadId == null) placeholderTitle(text) else null
        val thread = threadId ?: threadStore.createThread(title = placeholder, parent = null)
        val blocks = mutableListOf<Block>(Block.Text(text))
        if (attachCurrentScreen) attachScreen(thread, blocks)
        val enqueued = enqueue(RunOrigin.CHAT, thread, blocks, emptyMap(), null)
        if (placeholder != null && enqueued is Outcome.Ok) {
            refineTitle(thread = thread, message = text, placeholder = placeholder)
        }
        return enqueued
    }

    /** 从 [atSeq] 分叉并发起（[text] 为 null 时沿用源消息继续）。 */
    suspend fun launchFork(
        threadId: ThreadId,
        atSeq: Long,
        text: String?,
    ): Outcome<RunRecord> {
        val input = text?.let { listOf<Block>(Block.Text(it)) } ?: emptyList()
        val title = text?.let { placeholderTitle(it) }
        return agentSession.fork(threadId, atSeq, title = title).flatMap { forked ->
            enqueue(RunOrigin.FORK, forked, input, emptyMap(), null)
        }
    }

    /** 任务 Run 入队（docs/tech/07 §4.2/§4.3）：每次新建线程。 */
    suspend fun launchTask(
        task: Task,
        origin: RunOrigin,
        vars: Map<String, String>,
    ): Outcome<RunRecord> {
        val thread = threadStore.createThread(title = task.name, parent = null)
        return enqueue(origin, thread, listOf(Block.Text(task.goal)), vars, task)
    }

    /** 中断恢复 Run（docs/tech/04 §3.11）：同一 Thread 新起，input 为恢复提示文本。 */
    suspend fun launchResume(
        source: RunRecord,
        text: String,
    ): Outcome<RunRecord> =
        enqueue(
            RunOrigin.RESUME,
            source.threadId,
            listOf(Block.Text(text)),
            mapOf(RESUMED_FROM_KEY to source.id.value),
            null,
        )

    /** 取消尚未启动的 Run（已启动的由 `RunHandle.cancel` 处理）。 */
    fun cancelQueued(runId: RunId) {
        scheduler.cancelQueued(runId)
    }

    private suspend fun enqueue(
        origin: RunOrigin,
        threadId: ThreadId,
        input: List<Block>,
        vars: Map<String, String>,
        task: Task?,
    ): Outcome<RunRecord> {
        val now = clock.nowMillis()
        val record =
            RunRecord(
                id = RunId(ids.newId()),
                taskId = task?.id,
                threadId = threadId,
                origin = origin,
                status = RunStatus.QUEUED,
                triggerContext = vars,
                createdAt = now,
                startedAt = null,
                endedAt = null,
                summary = null,
                errorCode = null,
                pauseReason = null,
                waitingQuestion = null,
            )
        runStore.save(record)
        pending.put(record.id, PendingEntry(input = input, vars = vars, task = task))
        scheduler.enqueue(record)
        scheduler.trySchedule()
        return Outcome.Ok(record)
    }

    /** LLM 精炼标题：仅当标题仍是占位值（用户没手动改过名）才覆盖。 */
    private fun refineTitle(
        thread: ThreadId,
        message: String,
        placeholder: String,
    ) {
        scope.launch {
            val generated = titleGenerator.generate(message)?.trim().orEmpty()
            if (generated.isEmpty()) return@launch
            if (threadStore.titleOf(thread) == placeholder) threadStore.rename(thread, generated)
        }
    }

    /** 附当前屏幕截图（失败时只记日志，不阻塞发送）。 */
    private suspend fun attachScreen(
        thread: ThreadId,
        blocks: MutableList<Block>,
    ) {
        val result = screenReader.screenshot()
        val shot = result.getOrNull()
        val bytes = shot?.jpegBytes
        if (shot == null || bytes == null) {
            val error = (result as? Outcome.Err)?.error
            logger.w(
                TAG,
                "attach_screen_failed",
                "code" to (error?.code ?: "no_jpeg"),
                "detail" to (error?.message ?: "screenshot ok but no jpeg bytes"),
            )
            return
        }
        val fileRef = threadStore.saveBlob(thread, "screen-${clock.nowMillis()}.jpg", bytes)
        blocks += Block.Image(fileRef = fileRef, mime = "image/jpeg", width = shot.width, height = shot.height)
    }

    public companion object {
        /** [launchResume] 写入 `triggerContext` 的来源 Run id 键。 */
        public const val RESUMED_FROM_KEY: String = "resumedFrom"

        private const val TAG = "RunExecutor"
    }
}

/**
 * 首条消息 → 占位标题：取首个非空行、去首尾空白、截断；
 * 空消息给 null（列表沿用「未命名对话」，等 LLM 精炼或用户改名）。
 */
private fun placeholderTitle(text: String): String? =
    text
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(PLACEHOLDER_TITLE_CHARS)

/** 占位标题截断长度（字）。 */
private const val PLACEHOLDER_TITLE_CHARS: Int = 20
