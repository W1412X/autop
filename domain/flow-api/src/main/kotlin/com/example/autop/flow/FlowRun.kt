/**
 * 流程运行入口与调试控制（docs/design/flows.md §8、§9）。
 */
package com.example.autop.flow

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunHandle
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** 运行与调试选项（§8.4）。 */
public data class FlowRunOptions(
    /** 调试运行：单步与断点生效。 */
    val debug: Boolean = false,
    /** 到达前暂停的节点 id。 */
    val breakpoints: Set<String> = emptySet(),
    /** 从某节点开始。 */
    val startAt: String? = null,
    /** 只执行这一个节点。 */
    val onlyNode: String? = null,
    /** 预置变量（供 startAt / onlyNode 使用）。 */
    val overrides: JsonObject = JsonObject(emptyMap()),
    /** 带 `resumeKey` 的循环从保存的进度继续。 */
    val resume: Boolean = false,
)

/** 一次流程运行的启动请求。 */
public data class FlowRunRequest(
    val runId: RunId,
    val threadId: ThreadId,
    val flow: FlowDefinition,
    val params: JsonObject,
    /** 触发上下文（`trigger.` 前缀已去掉）。 */
    val trigger: JsonObject = JsonObject(emptyMap()),
    val taskId: TaskId? = null,
    /** 运行来源标签，如 `manual` / `schedule` / `condition` / `agent`。 */
    val origin: String,
    val options: FlowRunOptions = FlowRunOptions(),
    val config: ConfigSnapshot,
    /** 手机锁屏等需要的“允许工具”覆盖，null = 按流程设置。 */
    val toolFilter: Set<String>? = null,
)

/** 运行进度（给 UI 和悬浮窗）。 */
public data class FlowProgress(
    val currentNodeId: String? = null,
    val currentNodeName: String? = null,
    val completedNodes: Int = 0,
    val paused: Boolean = false,
    val pauseReason: PauseReason? = null,
    val pauseDetail: String? = null,
)

/** 失败暂停后用户的选择（§7.1）。 */
public enum class PauseResolution {
    RETRY_NODE,
    SKIP_NODE,
    END_RUN,
}

/** 调试控制。 */
public interface FlowDebugControl {
    /** 执行下一个节点后再次暂停。 */
    public fun step()

    /** 继续运行直到下一个断点。 */
    public fun resumeRun()

    public fun setBreakpoints(nodeIds: Set<String>)

    /** 对因失败暂停的运行给出处理方式。 */
    public fun resolvePause(resolution: PauseResolution)
}

/** 流程运行句柄；`steer` 为空操作。 */
public interface FlowRunHandle : RunHandle {
    public val progress: StateFlow<FlowProgress>

    public fun debug(): FlowDebugControl
}

/** 流程运行入口。 */
public interface FlowSession {
    public fun start(
        request: FlowRunRequest,
        scope: CoroutineScope,
    ): FlowRunHandle
}
