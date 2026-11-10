/**
 * 运行状态上报（agent-harness-overhaul WP3）：按 `agent.state.sources` 顺序采集，
 * 与“最近基线之后最后一次上报”比较，有非易变变化时写 RuntimeStateReported。
 * 上一次上报从事件日志取，不在内存里缓存（重启、压缩后自动全量）。
 */
package com.example.autop.agent.runtime.state

import com.example.autop.agent.RuntimeStateReported
import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.prompt.PromptRepository

/** 运行状态采集与上报。 */
internal class RuntimeStateTracker(
    private val sources: Map<String, RuntimeStateSource>,
    private val store: ThreadStore,
    private val prompts: PromptRepository,
    private val logger: Logger,
) {
    /** 需要时写一条上报；返回是否写了。 */
    suspend fun reportIfChanged(req: StateRequest): Boolean {
        val current = collect(req)
        val last =
            store
                .eventsSinceLatestBaseline(req.threadId)
                .mapNotNull { it.payload as? RuntimeStateReported }
                .lastOrNull()
        if (last == null) {
            write(req, current, full = true, text = render(ID_FULL, linesOf(current.values)))
            return true
        }
        val changed = current.filter { (key, entry) -> !entry.volatile && last.entries[key] != entry.text }
        val removed = last.entries.keys.filter { it !in current && it !in last.volatileKeys }
        if (changed.isEmpty() && removed.isEmpty()) return false
        val volatileNow = current.filter { it.value.volatile }.values
        val lines =
            changed.values.map { "- " + it.text } +
                removed.map { "- " + render(ID_REMOVED, mapOf(VAR_TEXT to last.entries.getValue(it))) } +
                volatileNow.map { "- " + it.text }
        write(req, current, full = false, text = render(ID_DELTA, mapOf(VAR_LINES to lines.joinToString("\n"))))
        return true
    }

    /** 当前完整状态文字（不写事件，get_device_state 用）。 */
    suspend fun fullText(req: StateRequest): String = render(ID_FULL, linesOf(collect(req).values))

    private suspend fun collect(req: StateRequest): LinkedHashMap<String, StateEntry> {
        val order = req.config[AgentConfig.STATE].sources
        val current = LinkedHashMap<String, StateEntry>()
        for (id in order) {
            val source = sources[id]
            if (source == null) {
                continue
            }
            when (val got = runCatchingCancellable(CODE_SOURCE_FAILED) { source.entries(req) }) {
                is Outcome.Err -> logger.w(TAG, "state_source_failed", "source" to id, error = got.error.cause)
                is Outcome.Ok -> for (entry in got.value) current["$id/${entry.key}"] = entry
            }
        }
        return current
    }

    private suspend fun write(
        req: StateRequest,
        current: LinkedHashMap<String, StateEntry>,
        full: Boolean,
        text: String,
    ) {
        store.append(
            req.threadId,
            req.runId,
            RuntimeStateReported(
                entries = current.mapValues { it.value.text },
                volatileKeys = current.filterValues { it.volatile }.keys,
                full = full,
                text = text,
            ),
        )
    }

    /** 每行前缀 `- `（[raw] 为真时入参已带前缀）。 */
    private fun linesOf(entries: Collection<StateEntry>): Map<String, String> = linesOf(entries.map { it.text })

    private fun linesOf(
        lines: List<String>,
        raw: Boolean = false,
    ): Map<String, String> = mapOf(VAR_LINES to lines.joinToString("\n") { if (raw) it else "- $it" })

    private fun render(
        id: String,
        vars: Map<String, String> = emptyMap(),
    ): String =
        prompts
            .render(id, vars)
            .getOrNull()
            ?.text
            .orEmpty()

    internal companion object {
        /** ToolContext.runScope 中本 Run tracker 的键。 */
        const val SCOPE_KEY: String = "runtime.state.tracker"

        private const val TAG = "RuntimeStateTracker"
        private const val CODE_SOURCE_FAILED = "agent.state_source_failed"
        private const val ID_FULL = "agent.state.full"
        private const val ID_DELTA = "agent.state.delta"
        private const val ID_REMOVED = "agent.state.removed"
        private const val VAR_LINES = "lines"
        private const val VAR_TEXT = "text"
    }
}
