/**
 * 任务创作工具箱（对标 flow-impl AuthorTools）：ask_user 提问；memory_search /
 * list_flows / inspect_screen 经 [ToolInvoker] 调**真实工具**（记忆检索、流程清单）；
 * propose_task 产出 [TaskDraft] 草稿。
 */
package com.example.autop.task.impl.author

import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.ToolOrigin
import com.example.autop.agent.UserInteraction
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.llm.Block
import com.example.autop.llm.ToolSchema
import com.example.autop.task.TaskDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 任务创作工具箱（[ToolInvoker] 委托真实工具，[propose] 维护草稿）。 */
internal class TaskAuthorTools(
    private val invokerFactory: () -> ToolInvokerFactory,
    private val configProvider: ConfigProvider,
    private val prompts: PromptRepository,
    private val ids: IdGenerator,
    private val threadStore: ThreadStore,
) {
    private val runId = RunId(ids.newId())
    private val threadId = ThreadId(ids.newId())
    private val invoker: ToolInvoker by lazy { invokerFactory().create(syntheticRequest(), TOOL_FILTER) }
    private val context: ToolContext by lazy {
        AuthorToolContext(runId, threadId, configProvider.current(), threadStore, invoker)
    }

    private fun syntheticRequest() =
        RunRequest(
            runId = runId,
            threadId = threadId,
            input = emptyList(),
            goal = null,
            vars = emptyMap(),
            config = configProvider.current(),
            toolFilter = TOOL_FILTER,
        )

    /** 工具声明：说明取自模板 `task.author.tools`（每行 `名称|说明`）。 */
    fun schemas(): List<ToolSchema> {
        val descriptions =
            ((prompts.render(TOOLS_TEMPLATE) as? Outcome.Ok)?.value?.text.orEmpty())
                .lines()
                .mapNotNull { line ->
                    line.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                }.toMap()
        val specs =
            listOf(
                toolSpec("ask_user", "task.author.tools") {
                    string("question", required = true)
                    array("options")
                },
                toolSpec("memory_search", "task.author.tools") { string("query", required = true) },
                toolSpec("list_flows", "task.author.tools") {},
                toolSpec("inspect_screen", "task.author.tools") {
                    string("mode", enum = listOf("a11y", "visual", "llm"))
                },
                toolSpec("propose_task", "task.author.tools") {
                    string("name", required = true)
                    string("goal", required = true)
                    string("triggerType", enum = listOf("manual", "once", "cron", "condition"))
                    string("at")
                    string("cron")
                    string("timezone")
                    string("source")
                    string("condition")
                    string("executorFlow")
                    boolean("needsScreen")
                    string("hint")
                },
            )
        return specs.map { ToolSchema(it.name, descriptions[it.name].orEmpty(), it.parameters) }
    }

    suspend fun run(
        name: String,
        args: JsonObject,
        activity: (String) -> Unit,
    ): String =
        when (name) {
            "inspect_screen" -> {
                activity("inspect_screen")
                call(
                    // ToolInvoker 按模型可见名调用（如 `observe`），不是工具 id（`tool.observe`）。
                    "observe",
                    buildJsonObject {
                        put("mode", args.str("mode") ?: "a11y")
                        put("full", true)
                    },
                )
            }
            "memory_search" ->
                call("memory_search", buildJsonObject { put("query", args.str("query").orEmpty()) })
            "list_flows" -> call("list_flows", buildJsonObject { })
            else -> """{"error":"unknown tool $name"}"""
        }

    /** 组装草稿（字段口径见 `task.author.spec`）。 */
    fun propose(args: JsonObject): TaskDraft =
        TaskDraft(
            name = args.str("name").orEmpty(),
            goal = args.str("goal").orEmpty(),
            triggerType = args.str("triggerType") ?: "manual",
            at = args.str("at"),
            cron = args.str("cron"),
            timezone = args.str("timezone"),
            source = args.str("source"),
            condition = args.str("condition"),
            executorFlow = args.str("executorFlow"),
            needsScreen = args.str("needsScreen")?.toBooleanStrictOrNull() ?: true,
            hint = args.str("hint").orEmpty(),
        )

    private suspend fun call(
        tool: String,
        args: JsonObject,
    ): String {
        val out = invoker.invoke(tool, args, context)
        val text = out.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        return if (out.ok) text else """{"error":${JsonPrimitive(out.error?.message ?: text)}}"""
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        const val TOOLS_TEMPLATE = "task.author.tools"

        /**
         * 只放行只读工具：创作会话不执行任何写屏/写记忆动作。
         * 这里是**工具 id**（路由器按 id 过滤；invoke 用模型可见名）。
         */
        val TOOL_FILTER: Set<String> = setOf("tool.observe", "tool.memory_search", "tool.list_flows")
    }
}

/** 创作会话的工具上下文：只读工具经 ToolInvoker，提问走 [UserInteraction]（此处不支持）。 */
private class AuthorToolContext(
    override val runId: RunId,
    override val threadId: ThreadId,
    override val config: ConfigSnapshot,
    override val threadStore: ThreadStore,
    private val invoker: ToolInvoker,
) : ToolContext {
    override val runScope: RunScopedValues = AuthorRunScope()
    override val tools: ToolInvoker = invoker
    override val quietActions: Boolean = true
    override val origin: ToolOrigin = ToolOrigin.AGENT
    override val originTag: String? = "task_author"

    override suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> =
        Outcome.Err(AppError(code = "task.author_no_interaction", message = "not available while authoring"))

    override fun emitProgress(text: String) = Unit
}

/** 创作会话的 run 级共享值（简单内存映射）。 */
private class AuthorRunScope : RunScopedValues {
    private val values = mutableMapOf<String, Any>()

    override fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T =
        synchronized(values) {
            @Suppress("UNCHECKED_CAST")
            values.getOrPut(key, create) as T
        }
}
