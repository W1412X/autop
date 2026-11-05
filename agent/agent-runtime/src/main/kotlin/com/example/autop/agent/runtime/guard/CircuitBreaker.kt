/**
 * 运行熔断（docs/tech/04 §3.7）：同错误重复、原地打转、重复动作三种检测。
 * 任一成功且屏幕有变化的动作重置相应计数。
 */
package com.example.autop.agent.runtime.guard

import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolOutput
import com.example.autop.core.config.AgentBreakerSection

/** 一次调用的熔断观察输入。 */
internal data class BreakerEntry(
    val toolId: String,
    val argsJson: String,
    val access: ToolAccess,
    val output: ToolOutput,
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

/** 熔断器（每次 Run 一个）。 */
internal class CircuitBreaker(
    private val cfg: AgentBreakerSection,
) {
    private var lastErrorKey: Pair<String, String>? = null
    private var sameErrorCount = 0
    private var stuckCount = 0
    private var lastArgsKey: Pair<String, String>? = null
    private var repeatCount = 0

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
        val changed = screenChanged(entry.output)
        val repeat = observeRepeat(entry, changed)
        if (repeat != null) return repeat
        return if (entry.output.ok) {
            lastErrorKey = null
            sameErrorCount = 0
            observeWriteScreen(entry, changed)
        } else {
            stuckCount = 0
            observeError(entry)
        }
    }

    /** 同一工具 + 同一 errorCode 连续计数。 */
    private fun observeError(entry: BreakerEntry): BreakerVerdict {
        val code = entry.output.error?.code ?: return BreakerVerdict.None
        val key = entry.toolId to code
        sameErrorCount = if (key == lastErrorKey) sameErrorCount + 1 else 1
        lastErrorKey = key
        return when (sameErrorCount) {
            cfg.sameErrorStop ->
                BreakerVerdict.Stop(
                    PROMPT_SAME_ERROR_STOP,
                    mapOf("tool" to entry.toolId, "count" to sameErrorCount.toString()),
                )
            cfg.sameErrorWarn ->
                BreakerVerdict.Warn(
                    PROMPT_SAME_ERROR_WARN,
                    mapOf("tool" to entry.toolId, "code" to code, "count" to sameErrorCount.toString()),
                )
            else -> BreakerVerdict.None
        }
    }

    /** 连续 WRITE_SCREEN 成功但屏幕无变化 = 原地打转。 */
    private fun observeWriteScreen(
        entry: BreakerEntry,
        changed: Boolean,
    ): BreakerVerdict {
        val watching = entry.access == ToolAccess.WRITE_SCREEN
        if (watching && changed) stuckCount = 0
        if (!watching || changed) return BreakerVerdict.None
        stuckCount += 1
        return when (stuckCount) {
            cfg.stuckStop ->
                BreakerVerdict.Stop(PROMPT_STUCK_STOP, mapOf("count" to stuckCount.toString()))
            cfg.stuckWarn ->
                BreakerVerdict.Warn(PROMPT_STUCK_WARN, mapOf("count" to stuckCount.toString()))
            else -> BreakerVerdict.None
        }
    }

    /** 同一工具 + 完全相同的参数连续计数。 */
    private fun observeRepeat(
        entry: BreakerEntry,
        changed: Boolean,
    ): BreakerVerdict? {
        val key = entry.toolId to entry.argsJson
        repeatCount = if (key == lastArgsKey) repeatCount + 1 else 1
        lastArgsKey = key
        if (changed) {
            repeatCount = 1
        }
        return if (repeatCount == cfg.repeatWarn) {
            BreakerVerdict.Warn(
                PROMPT_REPEAT_WARN,
                mapOf("tool" to entry.toolId, "count" to repeatCount.toString()),
            )
        } else {
            null
        }
    }

    /**
     * 屏幕是否变化：优先比较 `replay.postFingerprint` 与 `pre.fingerprint`，
     * 其次用动作后观测的 fingerprint；无法判断时按“有变化”处理，避免误熔断。
     */
    private fun screenChanged(output: ToolOutput): Boolean {
        val pre = output.replay?.pre?.fingerprint
        val now = output.replay?.postFingerprint ?: output.observation?.fingerprint
        return pre == null || now == null || now != pre
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
        const val PROMPT_STUCK_WARN = "agent.breaker.stuck_warn"
        const val PROMPT_STUCK_STOP = "agent.breaker.stuck_stop"
        const val PROMPT_REPEAT_WARN = "agent.breaker.repeat_warn"
    }
}
