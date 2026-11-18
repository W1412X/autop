/**
 * 悬浮球环境状态的纯逻辑（可单测）：由时间、空闲时长、充电与电量推出环境状态、晨间问候与手势反应序列。
 * 阈值与时长全部来自 `ui.ballAmbient`（[UiBallAmbientSection]）。
 */
package com.example.autop.feature.overlay.ball

import com.example.autop.core.config.UiBallAmbientSection
import com.example.autop.core.ui.component.ballface.BallAmbientMode
import com.example.autop.core.ui.component.ballface.BallReaction

/** 是否处于夜间时段（[UiBallAmbientSection.nightStartHour] 到 [UiBallAmbientSection.nightEndHour]，跨午夜）。 */
internal fun isNightHour(
    hour: Int,
    cfg: UiBallAmbientSection,
): Boolean =
    if (cfg.nightStartHour > cfg.nightEndHour) {
        hour >= cfg.nightStartHour || hour < cfg.nightEndHour
    } else {
        hour >= cfg.nightStartHour && hour < cfg.nightEndHour
    }

/**
 * 环境状态优先级：睡眠（夜间时段）> 充电 > 低电量（未充电）> 普通空闲。
 * [batteryPercent] 为 null（读不到电量）时不算低电量。
 */
internal fun ambientOf(
    hour: Int,
    charging: Boolean,
    batteryPercent: Int?,
    cfg: UiBallAmbientSection,
): BallAmbientMode =
    when {
        isNightHour(hour, cfg) -> BallAmbientMode.SLEEP
        charging -> BallAmbientMode.CHARGING
        batteryPercent != null && batteryPercent < cfg.lowBatteryPercent -> BallAmbientMode.TIRED
        else -> BallAmbientMode.NONE
    }

/** 是否处于晨间问候时段（[UiBallAmbientSection.morningStartHour] 到 [UiBallAmbientSection.morningEndHour]，不含结束）。 */
internal fun isMorningHour(
    hour: Int,
    cfg: UiBallAmbientSection,
): Boolean = hour >= cfg.morningStartHour && hour < cfg.morningEndHour

/** 晨间问候是否待播：处于时段内，且今天（[today]，epoch 天）还没播过。 */
internal fun morningGreetDue(
    hour: Int,
    lastGreetDay: Long?,
    today: Long,
    cfg: UiBallAmbientSection,
): Boolean = isMorningHour(hour, cfg) && lastGreetDay != today

/**
 * 一次触摸（点按、长按、唤出）要播放的反应序列。
 * - 睡眠中被触摸：只播唤醒（不再点按、不快转、不眨眼）。
 * - 晨间第一次触摸：哈欠，然后招手。
 * - 其它：播放触摸本身的反应。
 */
internal fun touchReactionsOf(
    kind: BallReaction,
    ambient: BallAmbientMode,
    morningDue: Boolean,
): List<BallReaction> =
    when {
        ambient == BallAmbientMode.SLEEP -> listOf(BallReaction.WAKE)
        morningDue -> listOf(BallReaction.YAWN, BallReaction.GREET)
        else -> listOf(kind)
    }

/** 反应的播放时长（毫秒）；没有计时的反应（拖动）返回 null。 */
internal fun reactionDurationMs(
    reaction: BallReaction,
    cfg: UiBallAmbientSection,
): Long? =
    when (reaction) {
        BallReaction.TAP -> cfg.tapMs
        BallReaction.LONG_PRESS -> cfg.longMs
        BallReaction.FLING -> cfg.flingMs
        BallReaction.REVEAL -> cfg.revealMs
        BallReaction.WAKE -> cfg.wakeMs
        BallReaction.YAWN -> cfg.yawnMs
        BallReaction.GREET -> cfg.greetMs
        BallReaction.DRAG -> null
    }

/** 松手时的甩出判定：平均速度（px/ms）不低于阈值即为甩出。 */
internal fun isFling(
    velocityPxPerMs: Float,
    cfg: UiBallAmbientSection,
): Boolean = velocityPxPerMs >= cfg.flingPxPerMs
