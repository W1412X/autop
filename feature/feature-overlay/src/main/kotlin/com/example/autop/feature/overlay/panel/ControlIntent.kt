/**
 * 控制过渡态（点击即反馈）：暂停/继续/中止点下后到状态落地前的「正在…」反馈。
 * 落地判定 [controlIntentSettled] 为纯函数（悬浮层状态化 UI 口径）；落地或超时即清除。
 */
package com.example.autop.feature.overlay.panel

import com.example.autop.core.common.RunId
import com.example.autop.feature.overlay.R
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunStatus

/** 控制意图种类。 */
public enum class ControlIntentKind {
    PAUSE,
    RESUME,
    STOP,
}

/** 控制意图：点下的动作 + 作用的 Run（换 Run 即失效，防过渡态串台）。 */
public data class ControlIntent(
    val kind: ControlIntentKind,
    val runId: RunId,
)

/**
 * 过渡态是否已落地：暂停→PAUSED、继续→离开 PAUSED、中止→Run 消失或收口；
 * active 已不是作用对象（换 Run）同样视为落地。
 */
public fun controlIntentSettled(
    intent: ControlIntent,
    active: ActiveRunInfo?,
): Boolean =
    when {
        active == null || active.run.id != intent.runId -> true
        intent.kind == ControlIntentKind.PAUSE -> active.run.status == RunStatus.PAUSED
        intent.kind == ControlIntentKind.RESUME -> active.run.status != RunStatus.PAUSED
        else -> active.run.status !in RunStatus.OPEN
    }

/** 过渡态文案资源（自明短语）。 */
public fun controlIntentLabelRes(kind: ControlIntentKind): Int =
    when (kind) {
        ControlIntentKind.PAUSE -> R.string.control_intent_pause
        ControlIntentKind.RESUME -> R.string.control_intent_resume
        ControlIntentKind.STOP -> R.string.control_intent_stop
    }
