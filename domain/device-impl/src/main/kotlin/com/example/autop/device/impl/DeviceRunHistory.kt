/**
 * Run 内设备状态（docs/tech/05 §3.4、§3.6；agent-harness-overhaul WP1.4）：
 * 只保留最近一次观测（差量与变化结论的基准），放在 ToolContext.runScope 中供同一 Run 共享。
 */
package com.example.autop.device.impl

import com.example.autop.agent.ToolContext
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.vision.FrameSignature

/** 观测的选择方式：自动或显式指定。 */
public sealed interface ObserveSelection {
    public data object Auto : ObserveSelection

    public data class Explicit(
        val mode: ObserveMode,
    ) : ObserveSelection
}

/** 一次被记录的观测（差量与变化结论的基准）。 */
public class RecordedObservation
    internal constructor(
        public val mode: ObserveMode,
        public val selection: ObserveSelection,
        public val screen: SerializedScreen,
        /** 该观测对应的快照（a11y 为真实树，visual 为视觉伪树，llm 为不含节点的基础快照）。 */
        public val snapshot: ScreenSnapshot,
        /** visual/llm 观测所用截图的帧签名；a11y 为 null。 */
        internal val frame: FrameSignature?,
    )

/** 一个 Run 内的设备观测历史（同一 Run 的屏幕类工具串行执行，见 WP5.2，无需加锁）。 */
public class DeviceRunHistory {
    /** 最近观测（任何方式）。 */
    public var last: RecordedObservation? = null

    /** 截图文件序号（`img/{seq}-{n}.jpg` 的 seq）。 */
    public var imageSeq: Int = 0

    /** LLM 观测缓存。 */
    internal var llmCache: LlmObservationCache = LlmObservationCache()

    /** 节点 id 计数器（a11y 观测分配 id 用，coordinate-node-action-split §3.2）。 */
    internal var nextNodeId: Int = 1

    /** 领一个新节点 id（Run 内单调递增、永不复用）。 */
    internal fun allocateNodeId(): Int = nextNodeId++

    public companion object {
        /** runScope 中观测历史的键。 */
        public const val HISTORY_KEY: String = "device.history"
    }
}

/** LLM 观测缓存条目。 */
internal class LlmObservationCache {
    internal var entry: Entry? = null

    internal class Entry(
        val rotation: Int,
        val signature: FrameSignature,
        val region: Rect?,
        val atMillis: Long,
        val model: ScreenModel,
    )
}

/** 取本 Run 的观测历史。 */
public fun ToolContext.deviceHistory(): DeviceRunHistory =
    runScope.getOrPut(DeviceRunHistory.HISTORY_KEY) {
        DeviceRunHistory()
    }
