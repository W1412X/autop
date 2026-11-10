/**
 * 可回放动作记录（docs/tech/04 §2.7，R1/A6）。
 *
 * 这些类型的内容对 agent-runtime 是不透明的字符串与 JSON，
 * 字段语义由设备领域定义（docs/tech/05 §13）。
 */
package com.example.autop.agent

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 某一时刻屏幕的语义样本，用于回放前比对。 */
@Serializable
public data class ReplayStateSample(
    @SerialName("packageName") val packageName: String?,
    @SerialName("activity") val activity: String?,
    /** 与 ObservationRef.fingerprint 同一算法。 */
    @SerialName("fingerprint") val fingerprint: String,
    /** 节点语义键集合（去重、排序），上限 cfg.device.replay.maxKeys。 */
    @SerialName("keys") val keys: List<String>,
    /**
     * 观测语义版本：1=旧口径（缺省，旧数据兼容），2=v2（合并修复 + 稳定键去易变文字），
     * 3=统一 JSON 观测（节点不再输出短 ID，键口径随 v3 序列化），4=按录制时的观测方式采样
     * （agent-harness-overhaul，新增 [observeMode]）。新采样写 [REPLAY_SEMANTICS_VERSION]；
     * 比对时版本不一致跳过 fingerprint/keys，只比 package/activity 并重新解析目标节点。
     */
    @SerialName("semanticsVersion") val semanticsVersion: Int = 1,
    /** 录制时使用的观测方式（a11y/visual/llm）；回放按它采样（WP2.8）。 */
    @SerialName("observeMode") val observeMode: String,
)

/** 新采样的观测语义版本（v4：按录制时的观测方式采样，agent-harness-overhaul）。 */
public const val REPLAY_SEMANTICS_VERSION: Int = 4

/** 一次可回放的动作记录（docs/tech/04 §2.7）。 */
@Serializable
public data class ReplayableAction(
    @SerialName("toolId") val toolId: String,
    /** 回放时传给工具的参数：坐标原样（不含抖动），另加 `_hit`；旧记录仍可能带 `_locator`。 */
    @SerialName("replayArgs") val replayArgs: JsonObject,
    /** 动作执行前的屏幕样本。 */
    @SerialName("pre") val pre: ReplayStateSample,
    /** 动作并等待稳定后的指纹。 */
    @SerialName("postFingerprint") val postFingerprint: String?,
    /** "node_action"（performNodeAction）| "gesture"（手势坐标）。 */
    @SerialName("execution") val execution: String,
    /** execution=gesture 时的实际坐标（屏幕像素），仅供诊断。 */
    @SerialName("gesture") val gesture: JsonObject? = null,
)

/** 消息来源（docs/tech/04 §2.7）：模型生成或回放合成。 */
@Serializable
public enum class MessageOrigin {
    MODEL,
    REPLAY,

    /** 流程节点合成（docs/design/flows.md §9.3）。 */
    FLOW,
}

/** 回放前比对用的端口：由 device-impl 实现，返回当前屏幕的语义样本。 */
public interface ReplayStateProbe {
    /** 按 [observeMode]（a11y/visual/llm）采样；[config] 为 Run 配置快照（WP2.8）。 */
    public suspend fun sample(
        observeMode: String,
        config: ConfigSnapshot,
    ): Outcome<ReplayStateSample>
}

/**
 * 回放目标重解析端口（docs/design/observation-modes-coordinate-actions §5.7）：
 * 按 `_hit`（或旧记录的 `_locator`）在当前屏幕重新定位，替换工具坐标。
 */
public interface ReplayTargetResolver {
    /** 返回替换坐标后的参数；[ReplayResolution.diverged] 表示目标未找到（按原参数执行）。 */
    public suspend fun resolve(
        toolId: String,
        args: JsonObject,
    ): Outcome<ReplayResolution>
}

/** 目标重解析结果。 */
public data class ReplayResolution(
    val args: JsonObject,
    val diverged: Boolean,
)

/** 任务回放脚本（docs/tech/04 §3.12）：一次成功 Run 中的可回放动作序列。 */
@Serializable
public data class ReplayScript(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("sourceRunId") val sourceRunId: RunId,
    /** 来源 Run 中成功的 WRITE_SCREEN 调用，按顺序。 */
    @SerialName("steps") val steps: List<ReplayableAction>,
)

/** 回放计划：脚本 + 执行参数，放进 RunRequest.replay。 */
public data class ReplayPlan(
    val script: ReplayScript,
    val config: ReplayConfig,
)

/** 回放执行参数（配置 `task.replay.*` 的运行期投影，WP10 消费）。 */
public data class ReplayConfig(
    val preStateWaitMs: Long,
    val pollMs: Long,
    val threshold: Double,
    val keysWeight: Double,
    val appWeight: Double,
)

/** 回放结果。 */
public data class ReplayOutcome(
    val completedSteps: Int,
    val totalSteps: Int,
    val diverged: Divergence?,
)

/** 回放对不上的位置与原因。 */
public data class Divergence(
    val stepIndex: Int,
    val reason: String,
    val score: Double?,
)
