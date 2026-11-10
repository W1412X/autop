@file:Suppress("ComplexCondition", "MagicNumber") // 观测缓存命中条件

package com.example.autop.flow.core

import com.example.autop.core.config.FlowRunSection
import com.example.autop.device.ScreenModel
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.Target
import com.example.autop.flow.core.expr.FlowException
import com.example.autop.flow.core.screen.TargetMatch
import com.example.autop.flow.core.screen.TargetMatcher
import kotlinx.coroutines.delay

/** 观测缓存、脏标记与目标解析（docs/design/flows.md §8.3）。 */
internal class ScreenService(
    private val ports: FlowPorts,
    private val cfg: FlowRunSection,
) {
    private class Cache(
        val mode: String,
        val at: Long,
        val observed: Observed,
        val fingerprint: Int,
    )

    private var cache: Cache? = null
    private var dirty = true
    private var actionBaseline: Int? = null
    private val nodeBaselines = HashMap<String, Int>()

    /** 屏幕可能已变化（写屏幕动作、等待、智能体、子流程之后）。 */
    fun markDirty(action: Boolean = false) {
        if (action) actionBaseline = cache?.fingerprint ?: actionBaseline
        dirty = true
    }

    /** 节点结束时记录屏幕指纹，供 `changedSince(since=节点)` 使用。 */
    fun nodeFinished(nodeId: String) {
        cache?.let { nodeBaselines[nodeId] = it.fingerprint }
    }

    /** 当前屏幕；[force] 或缓存失效时重新观测。 */
    suspend fun screen(
        mode: String?,
        defaultMode: String,
        force: Boolean = false,
    ): Observed {
        val wanted = mode ?: defaultMode
        val c = cache
        val now = ports.clock.nowMs()
        if (!force && !dirty && c != null && c.mode == wanted && now - c.at <= cfg.observeCacheMs) return c.observed
        val observed = ports.observe.observe(wanted)
        cache = Cache(wanted, ports.clock.nowMs(), observed, fingerprintOf(observed.screen))
        dirty = false
        return observed
    }

    /** 与基线比较屏幕是否变化；没有基线视为已变化。 */
    fun changedSince(since: String): Boolean {
        val current = cache?.fingerprint ?: return true
        val base = if (since == "lastAction") actionBaseline else nodeBaselines[since]
        return base == null || base != current
    }

    /** 轮询间隔：a11y 用 pollMs，其余用 slowPollMs。 */
    fun pollIntervalMs(mode: String): Long = if (mode == A11Y) cfg.pollMs else cfg.slowPollMs

    /** 解析目标；找不到时在 [waitMs] 内轮询。 */
    suspend fun find(
        frame: Frame,
        target: Target,
        wait: Boolean,
    ): TargetMatch {
        val rendered = renderTarget(frame, target)
        val mode = rendered.mode
        val defaultMode = frame.flow.settings.observeMode
        val deadline = ports.clock.nowMs() + if (wait) cfg.targetWaitMs else 0
        var first = true
        while (true) {
            val observed = screen(mode, defaultMode, force = !first)
            val match = TargetMatcher.resolve(observed.screen, rendered, rendered.value)
            if (match !is TargetMatch.NotFound || ports.clock.nowMs() >= deadline) return match
            first = false
            delay(pollIntervalMs(mode ?: defaultMode))
        }
    }

    /** 解析目标，不存在/多义时抛带错误码的异常。 */
    suspend fun require(
        frame: Frame,
        target: Target,
    ): TargetMatch.Found =
        when (val m = find(frame, target, wait = true)) {
            is TargetMatch.Found -> m
            is TargetMatch.Ambiguous ->
                throw FlowException(FlowErrorCodes.TARGET_AMBIGUOUS, "目标匹配到 ${m.count} 个元素，请指定 pick 或更精确的描述")
            TargetMatch.NotFound -> throw FlowException(FlowErrorCodes.TARGET_NOT_FOUND, "找不到目标：${describe(target)}")
        }

    private fun renderTarget(
        frame: Frame,
        target: Target,
    ): Target =
        target.copy(
            value = target.value?.let { frame.templates.renderString(it) },
            tag = target.tag?.let { frame.templates.renderString(it) },
            within = target.within?.let { renderTarget(frame, it) },
        )

    private fun describe(target: Target): String = target.value ?: target.tag ?: "(无描述)"

    /** 页面全部文字（text / desc / hint），用于文本条件与读取。 */
    fun pageText(screen: ScreenModel): String =
        screen
            .flatten()
            .flatMap {
                listOfNotNull(
                    it.text,
                    it.desc,
                    it.hint,
                )
            }.filter { it.isNotEmpty() }
            .joinToString("\n")

    /** 屏幕指纹：页面文字 + 节点数 + 节点状态（勾选、选中等），用于判断动作是否生效。 */
    fun fingerprintOf(screen: ScreenModel): Int =
        (pageText(screen).hashCode() * 31 + screen.nodeCount) * 31 + screen.flatten().map { it.st }.hashCode()

    /** 前台应用：窗口按 Z 序，取第一个 active 窗口的应用标识。 */
    fun foregroundApp(screen: ScreenModel): String? = screen.windows.firstOrNull { it.active }?.app

    companion object {
        const val A11Y: String = "a11y"
    }
}
