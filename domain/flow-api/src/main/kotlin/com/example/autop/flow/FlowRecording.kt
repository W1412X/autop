/**
 * 流程录制会话端口（docs/design/flows-recording-recovery.md V1、V5）。
 * 由悬浮球的录制面板驱动：开始 → 用户在目标应用里操作 → 撤销/完成/取消。
 * 完成后生成待确认的流程草稿，之后在编辑器里继续修改。
 */
package com.example.autop.flow

import com.example.autop.agent.RunOutcome
import com.example.autop.core.common.Outcome
import com.example.autop.device.PickedElement
import com.example.autop.device.UserActionType
import kotlinx.coroutines.flow.StateFlow

/** 录制会话的可观察状态。 */
public data class RecordingState(
    val active: Boolean = false,
    /** 当前已记录的操作数（撤销后减少）。 */
    val actionCount: Int = 0,
    /** 录制开始时所在的应用包名。 */
    val packageName: String? = null,
    /** 最近录到的操作（时间正序，数量见 `flow.recorder.recentShown`），供面板展示详情。 */
    val recent: List<RecordedStep> = emptyList(),
)

/** 一条已录到操作的展示摘要。 */
public data class RecordedStep(
    val type: UserActionType,
    /** 操作目标的文字、描述或 id 末段；没有可读标识时为 null。 */
    val target: String?,
)

/** 录制会话。同一时间只能有一个。 */
public interface RecordingSession {
    /** 录制状态。 */
    public val state: StateFlow<RecordingState>

    /** 开始录制；已在录制中时无效。 */
    public fun start()

    /** 撤销最近一次记录的操作。 */
    public fun undo()

    /**
     * 结束录制并保存为待确认草稿。[paramNames] 为 文字输入节点 id → 参数名（用户选择设为参数的）。
     * 成功返回草稿 id；没有可保存的操作或校验失败返回错误。
     */
    public suspend fun finish(
        name: String,
        paramNames: Map<String, String>,
    ): Outcome<String>

    /** 放弃录制，丢弃全部记录。 */
    public fun cancel()

    /** 当前录到的文字输入（节点 id 与输入文字），供用户选择设为参数。 */
    public fun typedInputs(): List<TypedInput>

    /** 用 AI 整理草稿：只改写名称与说明，不改动步骤。失败时返回原草稿的名称与说明。 */
    public suspend fun polishName(name: String): Outcome<PolishedName>

    /**
     * 试运行当前录制的草稿（不保存）。[scope] 为 [TrialScope.LAST_STEP] 时只运行最后一步。
     * 返回运行结果的状态文字（成功/失败），由调用方展示。
     */
    public suspend fun trialRun(scope: TrialScope): Outcome<RunOutcome>

    /** 把拾取到的元素加入录制（录制中才有效）。 */
    public fun addPicked(
        element: PickedElement,
        kind: PickKind,
    )
}

/** 一个录到的文字输入。 */
public data class TypedInput(
    val nodeId: String,
    val text: String,
)

/** AI 整理结果。 */
public data class PolishedName(
    val name: String,
    val description: String,
)

/** 试运行范围。 */
public enum class TrialScope {
    /** 整个草稿。 */
    ALL,

    /** 只运行最后一步（“试这一步”）。 */
    LAST_STEP,
}

/** 拾取元素后添加的步骤类型。 */
public enum class PickKind {
    /** 点击该元素。 */
    CLICK,

    /** 等待该元素出现。 */
    WAIT_ELEMENT,
}
