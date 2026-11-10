/**
 * 线程存储端口（docs/tech/04 §2.2）：事件 append 自增 seq、按事务提交。
 */
package com.example.autop.agent

import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * 用户从相册选取的图片的文件名前缀。历史渲染的「图片保留」规则据此区分用户图片与
 * 屏幕截图：截图只保留最近几张，用户图片是对话内容，始终保留。
 */
public const val USER_IMAGE_NAME_PREFIX: String = "pick-"

/** 线程列表项（`listThreads` 返回）。 */
public data class ThreadSummary(
    val threadId: ThreadId,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** 列表展示：最后一条用户输入前 60 字。 */
    val preview: String?,
    val lastRunStatus: RunOutcome?,
)

/** 线程事件与文件的持久化端口。 */
@Suppress("TooManyFunctions") // 接口的方法面就是存储的完整操作集，拆接口反而碎
public interface ThreadStore {
    /** 建线程；[parent] 为分叉来源 `(threadId, atSeq)`。 */
    public suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId

    /** 追加事件：seq 自增、事务内。 */
    public suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent

    /** 按 seq 区间读取事件。 */
    public suspend fun events(
        threadId: ThreadId,
        fromSeq: Long = 0,
        toSeq: Long = Long.MAX_VALUE,
    ): List<ThreadEvent>

    /** 从最近一条 Baseline 起（含 Baseline 本身）的全部事件。 */
    public suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent>

    /**
     * 订阅线程事件：先一次性发出 seq > [afterSeq] 的已有事件（一个列表），之后每条新事件发一个单元素列表。
     * 不丢事件、不重复（按 seq 去重），顺序按 seq 递增。
     *
     * 默认实现只转发 [observe] 的新事件（不回放 backlog），供未迁移的测试替身兼容；
     * 正式实现（ThreadStoreImpl）覆写为“先回放后实时”。
     */
    public fun observeFrom(
        threadId: ThreadId,
        afterSeq: Long,
    ): Flow<List<ThreadEvent>> = observe(threadId).filter { it.seq > afterSeq }.map { listOf(it) }

    /** 新事件推送（热流：订阅前的已有事件拿不到）。新代码一律用 [observeFrom]（不丢事件）。 */
    public fun observe(threadId: ThreadId): Flow<ThreadEvent>

    /** 线程级任何变更时发出（建线程 / append / 改名 / 删除）：对话列表等元数据视图刷新用（N184）。 */
    public fun observeChanges(): Flow<Unit>

    /** 保存二进制附件，返回 fileRef。 */
    public suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String

    /** 线程列表（倒序）。 */
    public suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary>

    /** 重命名线程。 */
    public suspend fun rename(
        threadId: ThreadId,
        title: String,
    )

    /** 单个线程的标题（线程不存在给 null）；顶栏与标题精炼的覆盖判定用。 */
    public suspend fun titleOf(threadId: ThreadId): String?

    /** 删除线程及其文件。 */
    public suspend fun delete(threadId: ThreadId)

    /** 可分叉的事件点：无未配对 ToolCall 的 ASSISTANT / TOOL 批次结束处（docs/tech/04 §3.11）。 */
    public suspend fun forkablePoints(threadId: ThreadId): List<Long>
}
