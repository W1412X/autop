/**
 * 运行熔断（agent-harness-overhaul WP2.9）：重复动作、同错误、原地打转三种检测，
 * 只消费工具收尾的变化结论（ScreenDelta），不再自行比较指纹。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.ScreenDelta
import com.example.autop.agent.ScreenDeltaKind
import com.example.autop.agent.ToolAccess
import com.example.autop.core.config.AgentBreakerSection

/** 一次调用的熔断观察输入。 */
internal data class BreakerEntry(
    val toolId: String,
    val argsJson: String,
    val access: ToolAccess,
    val ok: Boolean,
    val errorCode: String?,
    val screenDelta: ScreenDelta?,
)

/** 熔断判定结果。 */
internal sealed interface BreakerVerdict {
    data object None : BreakerVerdict

    data class Warn(
        val promptId: String,
        val vars: Map<String, String>,
    ) : BreakerVerdict

    data class Stop(
        val promptId: String,
        val vars: Map<String, String>,
    ) : BreakerVerdict
}

/** 一个计数器：达到 warn/stop 各只触发一次；计数归零时重新允许触发。 */
private class Counter(
    private val warn: Int,
    private val stop: Int,
) {
    var count: Int = 0
        private set

    private var warned = false
    private var stopped = false

    fun reset() {
        count = 0
        warned = false
        stopped = false
    }

    fun set(value: Int) {
        if (value < count) {
            warned = false
            stopped = false
        }
        count = value
    }

    fun increment() {
        count += 1
    }

    /** 返回本次应触发的级别；同一级别只触发一次。 */
    fun level(): Level? =
        when {
            stop > 0 && count >= stop && !stopped -> {
                stopped = true
                Level.STOP
            }
            count >= warn && !warned -> {
                warned = true
                Level.WARN
            }
            else -> null
        }

    enum class Level { WARN, STOP }
}

/** 熔断器（每次 Run 一个）。 */
internal class CircuitBreaker(
    private val cfg: AgentBreakerSection,
) {
    private var lastErrorKey: Pair<String, String>? = null
    private var lastArgsKey: Pair<String, String>? = null
    private val repeat = Counter(cfg.repeatWarn, cfg.repeatStop)
    private val sameError = Counter(cfg.sameErrorWarn, cfg.sameErrorStop)
    private val stuck = Counter(cfg.stuckWarn, cfg.stuckStop)

    /** 按调用顺序观察一批结果，返回最高级别判定（Stop > Warn）。 */
    fun observe(entries: List<BreakerEntry>): BreakerVerdict {
        var verdict: BreakerVerdict = BreakerVerdict.None
        for (entry in entries) {
            val v = observeOne(entry)
            verdict = maxOf(verdict, v)
        }
        return verdict
    }

    private fun observeOne(entry: BreakerEntry): BreakerVerdict {
        // 无法判断（UNKNOWN、null）按“未变化”计：盲操作里连续同动作也必须能被拦住。
        val changed = entry.screenDelta?.kind == ScreenDeltaKind.CHANGED
        observeRepeat(entry, changed)
        observeSameError(entry)
        observeStuck(entry, changed)
        return verdictOf()
    }

    /** 重复动作（所有工具）：屏幕有变化即重新计数。 */
    private fun observeRepeat(
        entry: BreakerEntry,
        changed: Boolean,
    ) {
        val key = entry.toolId to entry.argsJson
        if (changed) {
            repeat.set(1)
            lastArgsKey = key
            return
        }
        if (key == lastArgsKey) {
            repeat.increment()
        } else {
            repeat.set(1)
            lastArgsKey = key
        }
    }

    /** 同错误：同一工具 + 同一 errorCode 连续计数；成功即归零。 */
    private fun observeSameError(entry: BreakerEntry) {
        if (entry.ok) {
            sameError.reset()
            lastErrorKey = null
            return
        }
        val code = entry.errorCode ?: return
        val key = entry.toolId to code
        if (key == lastErrorKey) {
            sameError.increment()
        } else {
            sameError.set(1)
            lastErrorKey = key
        }
    }

    /** 原地打转：WRITE_SCREEN 成功但没变化；失败的调用不动计数。 */
    private fun observeStuck(
        entry: BreakerEntry,
        changed: Boolean,
    ) {
        if (changed) {
            stuck.reset()
            return
        }
        if (entry.access == ToolAccess.WRITE_SCREEN && entry.ok) stuck.increment()
    }

    /** 三个计数器各自出级别，取最高；同级优先 stuck > sameError > repeat。 */
    private fun verdictOf(): BreakerVerdict {
        val levels =
            listOf(
                STUCK to stuck.level(),
                SAME_ERROR to sameError.level(),
                REPEAT to repeat.level(),
            ).filter { it.second != null }
        val top = levels.maxOfOrNull { it.second ?: error("checked non-null") } ?: return BreakerVerdict.None
        val (which, level) = levels.first { it.second == top }
        return when (which) {
            STUCK -> verdict(level, PROMPT_STUCK_WARN, PROMPT_STUCK_STOP, mapOf(VAR_COUNT to stuck.count.toString()))
            SAME_ERROR ->
                verdict(
                    level,
                    PROMPT_SAME_ERROR_WARN,
                    PROMPT_SAME_ERROR_STOP,
                    mapOf(
                        VAR_TOOL to lastErrorKey?.first.orEmpty(),
                        VAR_CODE to lastErrorKey?.second.orEmpty(),
                        VAR_COUNT to sameError.count.toString(),
                    ),
                )
            else ->
                verdict(
                    level,
                    PROMPT_REPEAT_WARN,
                    PROMPT_REPEAT_STOP,
                    mapOf(VAR_TOOL to lastArgsKey?.first.orEmpty(), VAR_COUNT to repeat.count.toString()),
                )
        }
    }

    private fun verdict(
        level: Counter.Level?,
        warnId: String,
        stopId: String,
        vars: Map<String, String>,
    ): BreakerVerdict =
        when (level) {
            Counter.Level.STOP -> BreakerVerdict.Stop(stopId, vars)
            Counter.Level.WARN -> BreakerVerdict.Warn(warnId, vars)
            null -> BreakerVerdict.None
        }

    private fun maxOf(
        a: BreakerVerdict,
        b: BreakerVerdict,
    ): BreakerVerdict =
        when {
            a is BreakerVerdict.Stop || b is BreakerVerdict.Stop -> if (a is BreakerVerdict.Stop) a else b
            a is BreakerVerdict.Warn || b is BreakerVerdict.Warn -> if (a is BreakerVerdict.Warn) a else b
            else -> BreakerVerdict.None
        }

    private companion object {
        const val STUCK = "stuck"
        const val SAME_ERROR = "sameError"
        const val REPEAT = "repeat"

        const val PROMPT_SAME_ERROR_WARN = "agent.breaker.same_error_warn"
        const val PROMPT_SAME_ERROR_STOP = "agent.breaker.same_error_stop"
        const val PROMPT_STUCK_WARN = "agent.breaker.stuck_warn"
        const val PROMPT_STUCK_STOP = "agent.breaker.stuck_stop"
        const val PROMPT_REPEAT_WARN = "agent.breaker.repeat_warn"
        const val PROMPT_REPEAT_STOP = "agent.breaker.repeat_stop"

        const val VAR_TOOL = "tool"
        const val VAR_CODE = "code"
        const val VAR_COUNT = "count"
    }
}
