/**
 * 调试页的 ToolContext（docs/tech/10 WP4）：固定的调试 runId/threadId、
 * 内存版 [RunScopedValues]（整个调试会话共用一个，承载命中回执、llm 缓存与差量基线）、
 * `saveBlob` 写到 `cacheDir/debug/`（见 [DebugThreadStore]）、`askUser` 直接返回错误。
 */
package com.example.autop.feature.settings.debug

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot

/** 调试会话固定的 run / thread id。 */
internal const val DEBUG_RUN_ID: String = "run-debug"
internal const val DEBUG_THREAD_ID: String = "thread-debug"

/** 内存版 Run 内共享值容器（调试会话共用）。 */
internal class DebugRunValues : RunScopedValues {
    private val values = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T = synchronized(values) { values.getOrPut(key, create) as T }
}

/** 调试执行上下文。 */
internal class DebugToolContext(
    override val config: ConfigSnapshot,
    override val threadStore: ThreadStore,
    override val runScope: RunScopedValues,
) : ToolContext {
    override val runId: RunId = RunId(DEBUG_RUN_ID)

    override val threadId: ThreadId = ThreadId(DEBUG_THREAD_ID)

    override suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> =
        Outcome.Err(
            AppError(
                code = AgentErrorCodes.ASK_TIMEOUT,
                message = "ask_user is not available in debug session: $question",
            ),
        )

    override fun emitProgress(text: String) = Unit
}
