/**
 * 任务草稿 DTO（docs/tech/07 §6）：`task.draft.generate` 输出 JSON 的解码结构，
 * 与模板示例一致（trigger 复用 [Trigger] 的多态序列化）。
 */
package com.example.autop.task.impl.draft

import com.example.autop.core.common.TaskId
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import kotlinx.serialization.Serializable

/** LLM 输出的任务草稿（07 §6：name/goal/trigger/needsScreen/allowedTools）。 */
@Serializable
public data class TaskDraftDto(
    val name: String,
    val goal: String,
    val trigger: Trigger,
    val needsScreen: Boolean = true,
    val allowedTools: Set<String>? = null,
)

/** 草稿转待保存的 [Task]（07 §6：永远需要用户在编辑器里确认保存）。 */
public fun TaskDraftDto.toTask(
    id: TaskId,
    nowMillis: Long,
): Task =
    Task(
        id = id,
        name = name,
        goal = goal,
        trigger = trigger,
        needsScreen = needsScreen,
        allowedTools = allowedTools,
        createdAt = nowMillis,
        updatedAt = nowMillis,
    )

/**
 * 从模型输出中提取 JSON 对象文本：去掉代码围栏后取首 `{` 到末 `}`。
 * 找不到返回 null。
 */
public fun extractJsonObject(text: String): String? {
    val start = text.indexOf(FIRST_CHAR)
    val end = text.lastIndexOf(LAST_CHAR)
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}

private const val FIRST_CHAR = '{'
private const val LAST_CHAR = '}'
