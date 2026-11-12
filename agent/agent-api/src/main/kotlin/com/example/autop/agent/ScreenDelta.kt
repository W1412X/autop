/**
 * 屏幕变化结论（agent-harness-overhaul D4）。
 *
 * 已停用（tool_optimize D1）：只为读取旧事件保留，新事件恒为 null，生产代码不再构造。
 */
package com.example.autop.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 屏幕变化种类。 */
@Serializable
public enum class ScreenDeltaKind { CHANGED, UNCHANGED, UNKNOWN }

/** 一次屏幕变化结论：种类 + 稳定原因码。 */
@Serializable
public data class ScreenDelta(
    @SerialName("kind") val kind: ScreenDeltaKind,
    /** 原因码，见 [ScreenDeltaReasons]。 */
    @SerialName("reason") val reason: String,
)

/** 原因码（稳定字符串，进事件日志）。 */
public object ScreenDeltaReasons {
    /** 前台包名或 Activity 不同。 */
    public const val PAGE: String = "page"

    /** 同一页面，节点配对后有新增/消失/变化。 */
    public const val CONTENT: String = "content"

    /** 自动选择换了观测方式（说明能被高优先级方式读到的信息变了）。 */
    public const val MODE_SWITCH: String = "mode_switch"

    /** 同一页面，节点全部配上且无变化。 */
    public const val SAME: String = "same"

    /** 画面帧签名比较（llm 动作收尾、滚动分步）。 */
    public const val FRAME: String = "frame"

    /** 本 Run 还没有可比较的最近观测。 */
    public const val NO_BASELINE: String = "no_baseline"

    /** 动作后的观测失败。 */
    public const val OBSERVE_FAILED: String = "observe_failed"
}
