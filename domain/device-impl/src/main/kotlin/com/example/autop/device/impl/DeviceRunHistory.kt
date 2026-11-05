/**
 * Run 内设备状态（docs/tech/05 §3.4、§3.6）：各观测模式的差量基线、最近一次观测树与截图序号，
 * 放在 ToolContext.runScope 中（同一 Run 的工具调用共享）。
 */
package com.example.autop.device.impl

import com.example.autop.agent.ToolContext
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.vision.FrameSignature

/** 一个 Run 内的设备观测历史。 */
public class DeviceRunHistory {
    /** 各模式的差量基线（a11y/visual 分开，llm 恒发完整观测）。 */
    public val baselines: MutableMap<ObserveMode, SerializedScreen> = mutableMapOf()

    /** 最近一次观测树（工具无可用 a11y 树时供命中回执/策略主体回退）。 */
    public var lastTree: ScreenModel? = null

    /** 最近一次观测模式（动作收尾与回执来源）。 */
    public var lastMode: ObserveMode? = null

    /** 截图文件序号（`img/{seq}-{n}.jpg` 的 seq）。 */
    public var imageSeq: Int = 0

    /** LLM 观测缓存（帧差签名 + 区域 + TTL）。 */
    internal var llmCache: LlmObservationCache = LlmObservationCache()

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
