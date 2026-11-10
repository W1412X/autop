/**
 * 观测记录时的屏幕变化结论（agent-harness-overhaul WP2.2，规则顺序不可调换）。
 * 动作效果、熔断、节点动作学习只消费这个结论，不自行比较指纹。
 */
package com.example.autop.device.impl.observation

import com.example.autop.agent.ScreenDelta
import com.example.autop.agent.ScreenDeltaKind
import com.example.autop.agent.ScreenDeltaReasons
import com.example.autop.core.config.DeviceChangeSection
import com.example.autop.device.ObserveMode
import com.example.autop.device.core.ScreenNodeMatcher
import com.example.autop.device.impl.RecordedObservation
import com.example.autop.device.impl.vision.VisualFrameDiff

/** 屏幕变化结论计算。 */
internal object ScreenDeltas {
    fun of(
        prev: RecordedObservation?,
        cur: Prepared,
        match: ScreenNodeMatcher.Result?,
        cfg: DeviceChangeSection,
    ): ScreenDelta {
        if (prev == null) return ScreenDelta(ScreenDeltaKind.UNKNOWN, ScreenDeltaReasons.NO_BASELINE)
        if (prev.snapshot.foregroundPackage != cur.snapshot.foregroundPackage ||
            prev.snapshot.activity != cur.snapshot.activity
        ) {
            return ScreenDelta(ScreenDeltaKind.CHANGED, ScreenDeltaReasons.PAGE)
        }
        if (prev.mode != cur.mode) return ScreenDelta(ScreenDeltaKind.CHANGED, ScreenDeltaReasons.MODE_SWITCH)
        if (cur.mode == ObserveMode.LLM) {
            val before = prev.frame
            val after = cur.frame
            if (before == null || after == null) {
                return ScreenDelta(ScreenDeltaKind.UNKNOWN, ScreenDeltaReasons.NO_BASELINE)
            }
            val same = VisualFrameDiff.unchanged(before, after, cfg.frameMaxHamming, cfg.frameMinSsim)
            return ScreenDelta(
                if (same) ScreenDeltaKind.UNCHANGED else ScreenDeltaKind.CHANGED,
                ScreenDeltaReasons.FRAME,
            )
        }
        val result = match ?: return ScreenDelta(ScreenDeltaKind.UNKNOWN, ScreenDeltaReasons.NO_BASELINE)
        return if (result.empty) {
            ScreenDelta(ScreenDeltaKind.UNCHANGED, ScreenDeltaReasons.SAME)
        } else {
            ScreenDelta(ScreenDeltaKind.CHANGED, ScreenDeltaReasons.CONTENT)
        }
    }
}
