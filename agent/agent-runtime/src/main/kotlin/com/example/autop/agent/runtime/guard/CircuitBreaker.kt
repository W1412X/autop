/**
 * 运行熔断（tool_optimize D9）：同一工具连续返回同一错误时提示，超过上限强制收尾。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.core.config.AgentBreakerSection

/** 一次调用的熔断观察输入。 */
internal data class BreakerEntry(
    val toolId: String,
    val ok: Boolean,
    val errorCode: String?,
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
    private val sameError = Counter(cfg.sameErrorWarn, cfg.sameErrorStop)

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
        observeSameError(entry)
        return verdictOf()
    }

    /** 同错误：同一工具 + 同一 errorCode 连续计数；成功即归零；换错误码重新计数。 */
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

    private fun verdictOf(): BreakerVerdict =
        verdict(
            sameError.level(),
            PROMPT_SAME_ERROR_WARN,
            PROMPT_SAME_ERROR_STOP,
            mapOf(
                VAR_TOOL to lastErrorKey?.first.orEmpty(),
                VAR_CODE to lastErrorKey?.second.orEmpty(),
                VAR_COUNT to sameError.count.toString(),
            ),
        )

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
        const val PROMPT_SAME_ERROR_WARN = "agent.breaker.same_error_warn"
        const val PROMPT_SAME_ERROR_STOP = "agent.breaker.same_error_stop"

        const val VAR_TOOL = "tool"
        const val VAR_CODE = "code"
        const val VAR_COUNT = "count"
    }
}
