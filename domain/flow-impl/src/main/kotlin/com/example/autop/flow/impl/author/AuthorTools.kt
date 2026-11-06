package com.example.autop.flow.impl.author

import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserInteraction
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.flow.impl.ports.FlowToolContext
import com.example.autop.llm.Block
import com.example.autop.llm.ToolSchema
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 创作 AI 可用的工具（docs/design/flows.md §12.2）。`ask_user` / `demonstrate` / `propose_flow`
 * 需要会话状态，由会话自己处理；其余只读工具在这里。活动提示只传稳定的代码，界面按代码取文案。
 */
internal class AuthorTools(
    private val d: AuthorDeps,
) {
    private val invoker: ToolInvoker by lazy { d.invokerFactory().create(syntheticRequest(), TOOL_FILTER) }
    private val context: ToolContext by lazy {
        FlowToolContext(
            runId = runId,
            threadId = threadId,
            config = d.configProvider.current(),
            threadStore = d.threads,
            interaction = NoInteraction,
            invoker = invoker,
            progress = {},
        )
    }
    private val runId = RunId(d.ids.newId())
    private val threadId = ThreadId(d.ids.newId())

    private fun syntheticRequest() =
        RunRequest(
            runId = runId,
            threadId = threadId,
            input = emptyList(),
            goal = null,
            vars = emptyMap(),
            config = d.configProvider.current(),
            toolFilter = TOOL_FILTER,
        )

    /** 工具声明：说明取自模板 `flow.author.tools`（每行 `名称|说明`）。 */
    fun schemas(): List<ToolSchema> {
        val descriptions =
            ((d.prompts.render(TOOLS_TEMPLATE) as? Outcome.Ok)?.value?.text.orEmpty())
                .lines()
                .mapNotNull { line ->
                    line.split('|', limit = 2).takeIf { it.size == 2 }?.let {
                        it[0].trim() to
                            it[1].trim()
                    }
                }.toMap()
        val specs =
            listOf(
                toolSpec("ask_user", "flow.author.tools") {
                    string("question", required = true)
                    array("options")
                },
                toolSpec(
                    "inspect_screen",
                    "flow.author.tools",
                ) { string("mode", enum = listOf("a11y", "visual", "llm")) },
                toolSpec("demonstrate", "flow.author.tools") { string("goal", required = true) },
                toolSpec("list_tools", "flow.author.tools") {},
                toolSpec("list_flows", "flow.author.tools") {},
                toolSpec("read_flow", "flow.author.tools") { string("flow", required = true) },
                toolSpec("list_endpoints", "flow.author.tools") {},
                toolSpec("memory_search", "flow.author.tools") { string("query", required = true) },
                toolSpec("propose_flow", "flow.author.tools") { anyObject("flow", required = true) },
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
                activity(ACTIVITY_INSPECT)
                call(
                    "observe",
                    buildJsonObject {
                        put("mode", args.str("mode") ?: "a11y")
                        put("full", true)
                    },
                )
            }
            "list_tools" -> listTools()
            "list_flows" -> listFlows()
            "read_flow" -> readFlow(args.str("flow").orEmpty())
            "list_endpoints" -> listEndpoints()
            "memory_search" -> call("memory_search", buildJsonObject { put("query", args.str("query").orEmpty()) })
            else -> """{"error":"unknown tool $name"}"""
        }

    private suspend fun call(
        tool: String,
        args: JsonObject,
    ): String {
        val out = invoker.invoke(tool, args, context)
        val text = out.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
        return if (out.ok) text else """{"error":${JsonPrimitive(out.error?.message ?: text)}}"""
    }

    private fun listTools(): String =
        buildJsonArray {
            d.probe().targets().forEach { t ->
                add(
                    buildJsonObject {
                        put("name", t.name)
                        put("access", t.access.name.lowercase())
                        put(
                            "params",
                            buildJsonArray {
                                t.params.forEach { p ->
                                    add(
                                        buildJsonObject {
                                            put("name", p.name)
                                            put("type", p.kind.name.lowercase())
                                            put("required", p.required)
                                            if (p.enumValues.isNotEmpty()) {
                                                put(
                                                    "enum",
                                                    JsonArray(p.enumValues.map(::JsonPrimitive)),
                                                )
                                            }
                                            put("description", p.description)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }.toString()

    private suspend fun listFlows(): String =
        buildJsonArray {
            d.store.listAll().forEach { f ->
                add(
                    buildJsonObject {
                        put("id", f.id.value)
                        put("name", f.definition.name)
                        put("description", f.definition.description)
                        put("params", JsonArray(f.definition.params.map { JsonPrimitive(it.name) }))
                    },
                )
            }
        }.toString()

    private suspend fun readFlow(nameOrId: String): String =
        d.store.resolve(nameOrId)?.let {
            com.example.autop.core.common.AutopJson.default.encodeToString(
                com.example.autop.flow.FlowDefinition
                    .serializer(),
                it.definition,
            )
        } ?: """{"error":"flow not found"}"""

    private suspend fun listEndpoints(): String =
        buildJsonArray {
            d.endpoints.observeAll().first().forEach { e ->
                add(
                    buildJsonObject {
                        put("name", e.name)
                        put("baseUrl", e.baseUrl)
                    },
                )
            }
        }.toString()

    /** 示范：智能体按目标真实操作一遍，返回动作序列（工具、参数、结果摘要）。 */
    suspend fun demonstrate(
        goal: String,
        activity: (String) -> Unit,
    ): String {
        activity(ACTIVITY_DEMONSTRATE)
        val service = d.runService()
        val record: RunRecord =
            when (val started = service.startChat(null, goal, false)) {
                is Outcome.Ok -> started.value
                is Outcome.Err -> return """{"error":${JsonPrimitive(started.error.message)}}"""
            }
        val finished = service.observeRun(record.id).first { it.status in TERMINAL }
        val events = d.threads.events(record.threadId)
        val calls = HashMap<String, Pair<String, String>>()
        val steps = mutableListOf<JsonElement>()
        for (event in events) {
            when (val p = event.payload) {
                is AssistantMessage ->
                    p.blocks.filterIsInstance<Block.ToolCall>().forEach { calls[it.id] = it.name to it.argsJson }
                is ToolResultEvent -> {
                    val (tool, args) = calls[p.callId] ?: (p.toolId to "{}")
                    val text =
                        p.blocks
                            .filterIsInstance<Block.Text>()
                            .joinToString("\n") { it.text }
                            .take(RESULT_CHARS)
                    steps +=
                        buildJsonObject {
                            put("tool", tool)
                            put("args", args)
                            put("ok", p.ok)
                            put("result", text)
                        }
                }
                else -> Unit
            }
        }
        return buildJsonObject {
            put("status", finished.status.name.lowercase())
            finished.summary?.let { put("summary", it) }
            put("steps", JsonArray(steps))
        }.toString()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private object NoInteraction : UserInteraction {
        override suspend fun ask(
            runId: RunId,
            question: String,
            options: List<String>,
            timeoutMs: Long,
        ): Outcome<String> =
            Outcome.Err(AppError(code = "flow.author_no_interaction", message = "not available while authoring"))
    }

    private companion object {
        const val TOOLS_TEMPLATE = "flow.author.tools"
        const val ACTIVITY_INSPECT = "inspect_screen"
        const val ACTIVITY_DEMONSTRATE = "demonstrate"
        const val RESULT_CHARS = 600
        val TERMINAL =
            setOf(
                RunStatus.SUCCEEDED,
                RunStatus.FAILED,
                RunStatus.CANCELLED,
                RunStatus.EXPIRED,
                RunStatus.INTERRUPTED,
                RunStatus.BUDGET_EXHAUSTED,
            )
        val TOOL_FILTER: Set<String> = setOf("tool.observe", "tool.memory_search")
    }
}
