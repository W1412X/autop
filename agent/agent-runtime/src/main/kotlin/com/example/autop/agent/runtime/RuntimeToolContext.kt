/**
 * ToolContext 的 runtime 实现（docs/tech/04 §2.3）：ask_user 走 UserInteraction 端口，
 * 进度转 ToolProgress 事件；Run 内共享值容器的简单实现。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import java.util.concurrent.ConcurrentHashMap

/** 运行期共享值容器：键稳定的惰性单例。 */
internal class RunScopeValues : RunScopedValues {
    private val values = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST") // 键稳定、类型由调用方约定
    override fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T = values.getOrPut(key) { create() } as T
}

/** 工具执行上下文（每次 Run 一个；runId/threadId/config 取自 RunRequest）。 */
internal class RuntimeToolContext(
    private val req: RunRequest,
    override val threadStore: ThreadStore,
    private val ask: suspend (String, List<String>, Long) -> Outcome<String>,
    private val progress: (String) -> Unit,
    override val modelVision: Boolean = false,
    override val tools: ToolInvoker? = null,
) : ToolContext {
    override val runId get() = req.runId
    override val threadId get() = req.threadId
    override val config get() = req.config
    override val runScope: RunScopedValues = RunScopeValues()

    override suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> = ask(question, options, timeoutMs)

    override fun emitProgress(text: String) = progress(text)
}

/** 无 UserInteraction 端口时的默认回答：按超时处理（docs/tech/04 §3.9）。 */
internal fun noUserInteraction(): Outcome<String> =
    Outcome.Err(
        AppError(
            code = AgentErrorCodes.ASK_TIMEOUT,
            message = "user interaction port is not bound",
            hint = "按默认继续或结束",
        ),
    )
