/**
 * 条件任务的触发闸门（docs/tech/07 §2）：冷却期、每日上限（按 Task 时区的自然日）、
 * 去重（最近 `task.dedupe.window` 个 key）。纯函数，状态由调用方持久化。
 */
package com.example.autop.task.core

import com.example.autop.task.Task
import com.example.autop.task.Trigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 触发闸门。 */
public object FireGate {
    private val DAY_KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 闸门判定结果。 */
    public sealed interface Verdict {
        /** 放行。 */
        public data object Pass : Verdict

        /** 拦截（携带原因）。 */
        public data class Blocked(
            val reason: Reason,
        ) : Verdict

        public enum class Reason {
            COOLDOWN,
            DAILY_LIMIT,
            DUPLICATE,
        }
    }

    /**
     * 检查是否放行。
     *
     * @param cooldownMs 冷却期（来自任务）。
     * @param dailyLimit 每日上限（来自任务）。
     * @param zone 任务时区（自然日按它计算）。
     */
    @Suppress("ReturnCount", "LongParameterList") // 闸门逐条提前返回；参数即各条规则的判定点
    public fun check(
        state: TriggerState,
        key: String,
        nowMillis: Long,
        cooldownMs: Long,
        dailyLimit: Int,
        zone: ZoneId,
    ): Verdict {
        val last = state.lastFiredAt
        if (last != null && nowMillis - last < cooldownMs) return Verdict.Blocked(Verdict.Reason.COOLDOWN)
        val dayKey = dayKey(nowMillis, zone)
        val firedToday = if (state.dayKey == dayKey) state.firedToday else 0
        if (firedToday >= dailyLimit) return Verdict.Blocked(Verdict.Reason.DAILY_LIMIT)
        if (key.isNotEmpty() && state.recentKeys.contains(key)) return Verdict.Blocked(Verdict.Reason.DUPLICATE)
        return Verdict.Pass
    }

    /** 记录一次触发，返回新状态。 */
    public fun record(
        state: TriggerState,
        key: String,
        nowMillis: Long,
        zone: ZoneId,
        window: Int,
    ): TriggerState {
        val dayKey = dayKey(nowMillis, zone)
        val firedToday = if (state.dayKey == dayKey) state.firedToday else 0
        val keys = (state.recentKeys + key).takeLast(window.coerceAtLeast(0))
        return TriggerState(
            lastFiredAt = nowMillis,
            dayKey = dayKey,
            firedToday = firedToday + 1,
            recentKeys = keys,
        )
    }

    /** 取任务的条件触发配置（非条件任务返回 null）。 */
    public fun conditionOf(task: Task): Trigger.Condition? = task.trigger as? Trigger.Condition

    /** 自然日键（任务时区）。 */
    public fun dayKey(
        nowMillis: Long,
        zone: ZoneId,
    ): String = Instant.ofEpochMilli(nowMillis).atZone(zone).format(DAY_KEY_FORMAT)
}

/** 一个任务的触发状态（持久化于 `trigger_state`）。 */
public data class TriggerState(
    val lastFiredAt: Long? = null,
    val dayKey: String? = null,
    val firedToday: Int = 0,
    val recentKeys: List<String> = emptyList(),
)
