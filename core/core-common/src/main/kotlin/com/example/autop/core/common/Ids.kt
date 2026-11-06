package com.example.autop.core.common

import kotlinx.serialization.Serializable

/** 一次执行的 id。 */
@JvmInline
@Serializable
public value class RunId(
    val value: String,
)

/** 消息流（会话）的 id。 */
@JvmInline
@Serializable
public value class ThreadId(
    val value: String,
)

/** 任务定义的 id。 */
@JvmInline
@Serializable
public value class TaskId(
    val value: String,
)

/** 流程定义的 id（放在 core-common，避免 task-api 依赖 flow-api）。 */
@JvmInline
@Serializable
public value class FlowId(
    val value: String,
)

/**
 * id 生成器。实现必须是**时间有序**的（docs/tech/02 §1.2，UUIDv7 风格），
 * 这样按 id 排序即按创建时间排序，无需额外索引。
 */
public interface IdGenerator {
    public fun newId(): String
}
