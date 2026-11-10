/**
 * Run 排队（docs/tech/07 §4.3）：创建 RunRecord 入队（对话沿用线程、任务每次
 * 新建线程）；门条件检查在 [RunScheduler]，执行在 [RunRunner]。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentSession
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.USER_IMAGE_NAME_PREFIX
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.flatMap
import com.example.autop.core.common.getOrNull
import com.example.autop.device.ScreenReader
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowRunOptions
import com.example.autop.llm.Block
import com.example.autop.task.ChatTitleGenerator
import com.example.autop.task.ImageAttachment
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
        images: List<ImageAttachment>,
    ): Outcome<RunRecord> {
        val placeholder = if (threadId == null) placeholderTitle(text) else null
        val thread = threadId ?: threadStore.createThread(title = placeholder, parent = null)
        // 纯图片消息没有文本块：部分协议拒绝空 text 块。
        val blocks = mutableListOf<Block>()
        if (text.isNotBlank()) blocks += Block.Text(text)
        saveImages(thread, images, blocks)
        if (attachCurrentScreen) attachScreen(thread, blocks)
        val enqueued = enqueue(RunOrigin.CHAT, thread, blocks, UserMessageKind.INPUT, emptyMap(), null)
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
            enqueue(RunOrigin.FORK, forked, input, if (text == null) null else UserMessageKind.INPUT, emptyMap(), null)
        }
    }

    /** 任务 Run 入队（docs/tech/07 §4.2/§4.3）：每次新建线程；不写输入，目标由 GoalContributor 注入一次。 */
    suspend fun launchTask(
        task: Task,
        origin: RunOrigin,
        vars: Map<String, String>,
    ): Outcome<RunRecord> {
        val thread = threadStore.createThread(title = task.name, parent = null)
        return enqueue(origin, thread, emptyList(), null, vars, task)
    }

    /** 独立流程运行入队（流程页「运行」与调试，docs/design/flows.md §9.2）。 */
    suspend fun launchFlow(
        flow: FlowDefinition,
        params: JsonObject,
        options: FlowRunOptions,
        needsScreen: Boolean,
    ): Outcome<RunRecord> {
        val thread = threadStore.createThread(title = flow.name, parent = null)
        val launch = FlowLaunch(flow, params, options, needsScreen)
        return enqueue(RunOrigin.TASK_MANUAL, thread, emptyList(), null, emptyMap(), null, launch)
    }

    /** 中断恢复 Run（docs/tech/04 §3.11）：同一 Thread 新起，input 为恢复提示文本（INJECTED，不显示成用户气泡）。 */
    suspend fun launchResume(
        source: RunRecord,
        text: String,
    ): Outcome<RunRecord> =
        enqueue(
            RunOrigin.RESUME,
            source.threadId,
            listOf(Block.Text(text)),
            UserMessageKind.INJECTED,
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
        inputKind: UserMessageKind?,
        vars: Map<String, String>,
        task: Task?,
        flow: FlowLaunch? = null,
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
        // 输入入队即落盘（WP8.1）：先写事件日志再暂存入队，进程被杀也不丢用户原话。
        if (input.isNotEmpty() && inputKind != null) {
            threadStore.append(threadId, record.id, UserMessage(blocks = input, kind = inputKind))
        }
        pending.put(record.id, PendingEntry(vars = vars, task = task, flow = flow))
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

    /** 用户选取的图片落盘并追加为 Image 块；文件名带 [USER_IMAGE_NAME_PREFIX]，供历史渲染区分于屏幕截图。 */
    private suspend fun saveImages(
        thread: ThreadId,
        images: List<ImageAttachment>,
        blocks: MutableList<Block>,
    ) {
        val stamp = clock.nowMillis()
        images.forEachIndexed { index, image ->
            val ext = if (image.mime == MIME_PNG) "png" else "jpg"
            val fileRef = threadStore.saveBlob(thread, "$USER_IMAGE_NAME_PREFIX$stamp-$index.$ext", image.bytes)
            blocks += Block.Image(fileRef = fileRef, mime = image.mime, width = image.width, height = image.height)
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
        private const val MIME_PNG = "image/png"
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
