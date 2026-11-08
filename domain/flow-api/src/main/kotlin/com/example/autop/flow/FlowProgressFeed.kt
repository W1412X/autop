/**
 * 流程运行进度登记（进度互通，P1-2/P1-3）：run_flow（对话内）与任务/流程页起的流程
 * 都把运行登记到**根 Run** 名下，悬浮球/进度窗直读根 Run 即可看到流程当前节点，
 * 并可"只停流程、不动根 Run"（取消粒度）。
 */
package com.example.autop.flow

import com.example.autop.core.common.RunId
import kotlinx.coroutines.flow.StateFlow

/** 一次进行中流程的进度快照（登记表的值）。 */
public data class FlowRunProgress(
    /** 流程名。 */
    val flowName: String,
    /** 节点级进度。 */
    val progress: FlowProgress,
)

/** 流程进度登记与取消端口（flow-impl 实现；task/overlay 侧只读 + cancel）。 */
public interface FlowProgressFeed {
    /** 根 Run id.value → 进行中流程进度（运行结束自动注销）。 */
    public val active: StateFlow<Map<String, FlowRunProgress>>

    /**
     * 登记一次流程运行（由起跑方调用：run_flow 挂 ctx.runId、任务流程挂 RunRecord id）。
     * 句柄结束后自动注销；重复登记同根 Run 时覆盖旧条目。
     */
    public fun register(
        rootRunId: RunId,
        flowName: String,
        handle: FlowRunHandle,
    )

    /** 只停止该流程（[FlowRunHandle.cancel]），不动根 Run；无登记返回 false。 */
    public fun cancel(rootRunId: RunId): Boolean
}
