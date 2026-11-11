package com.example.autop.flow.impl.author

import com.example.autop.agent.RunRequest
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolInvoker
import com.example.autop.agent.UserInteraction
import com.example.autop.agent.toolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.flow.Condition
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.core.contract.ContractChecker
import com.example.autop.flow.core.contract.ContractIssue
import com.example.autop.flow.core.evidence.ContractCandidate
import com.example.autop.flow.core.evidence.EvidenceExtractor
import com.example.autop.flow.core.evidence.EvidencePack
import com.example.autop.flow.core.evidence.EvidenceScreen
import com.example.autop.flow.core.evidence.EvidenceStep
import com.example.autop.flow.core.evidence.conditionHolds
import com.example.autop.flow.core.evidence.describeCondition
import com.example.autop.flow.core.evidence.screenTextOf
import com.example.autop.flow.impl.ports.FlowToolContext
import com.example.autop.flow.impl.tools.evidenceStepsOf
import com.example.autop.llm.Block
import com.example.autop.llm.ToolSchema
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
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
    /** 最近一次 demonstrate 的证据（read_evidence / check_contract 用）。 */
    private var evidenceRuns: List<List<EvidenceStep>> = emptyList()

    private val extractor = EvidenceExtractor()

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
                toolSpec("read_evidence", "flow.author.tools") { integer("step") },
                toolSpec("check_contract", "flow.author.tools") {
                    anyObject("cond", required = true)
                    array("steps", itemType = "integer")
                },
                toolSpec("propose_flow", "flow.author.tools") {
                    anyObject("flow", required = true)
                    anyObject("trace")
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
            "read_evidence" -> readEvidence(args.str("step")?.toIntOrNull())
            "check_contract" -> checkContract(args)
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

    /** 示范：智能体按目标真实操作一遍，返回证据包（逐步差异与契约候选，§3.1）。 */
    suspend fun demonstrate(
        goal: String,
        activity: (String) -> Unit,
    ): String {
        activity(ACTIVITY_DEMONSTRATE)
        val service = d.runService()
        val record: RunRecord =
            when (val started = service.startChat(null, goal)) {
                is Outcome.Ok -> started.value
                is Outcome.Err -> return """{"error":${JsonPrimitive(started.error.message)}}"""
            }
        val finished = service.observeRun(record.id).first { it.status in TERMINAL }
        val events = d.threads.events(record.threadId)
        evidenceRuns = listOf(evidenceStepsOf(events, record.id))
        val pack = extractor.extract(evidenceRuns)
        return buildJsonObject {
            put("status", finished.status.name.lowercase())
            finished.summary?.let { put("summary", it) }
            put("evidence", AutopJson.default.encodeToJsonElement(EvidencePack.serializer(), pack))
        }.toString()
    }

    /**
     * 契约核验（propose_flow 用）：[trace] 为节点 id → 证据步序号（创作元数据 authorTrace）；
     * 没有证据或没有对应关系时不做核验。
     */
    fun contractIssues(
        flow: FlowDefinition,
        trace: Map<String, Int>,
    ): List<ContractIssue> {
        if (evidenceRuns.isEmpty() || trace.isEmpty()) return emptyList()
        return ContractChecker().check(flow, evidenceRuns) { node -> trace[node.id] }
    }

    /** 读取证据包里某一步的完整证据（省略 step 返回全部摘要）。 */
    private fun readEvidence(step: Int?): String {
        val runs = evidenceRuns
        if (runs.isEmpty()) return """{"error":"no evidence yet; call demonstrate first"}"""
        val primary = runs.first()
        val pack = extractor.extract(runs)
        val picked = pack.steps.filter { step == null || it.index == step }
        return buildJsonObject {
            put(
                "steps",
                JsonArray(
                    picked.map { se ->
                        buildJsonObject {
                            put("index", se.index)
                            put("tool", se.tool)
                            put("ok", se.ok)
                            put("weak", se.weak)
                            put("appChange", se.appChange)
                            put("appeared", JsonArray(se.appeared.map(::JsonPrimitive)))
                            put("disappeared", JsonArray(se.disappeared.map(::JsonPrimitive)))
                            put("changed", JsonArray(se.changed.map(::JsonPrimitive)))
                            put("persistent", JsonArray(se.persistent.map(::JsonPrimitive)))
                            se.hit?.let { put("hit", it) }
                            put(
                                "candidates",
                                JsonArray(
                                    se.candidates.map {
                                        AutopJson.default.encodeToJsonElement(ContractCandidate.serializer(), it)
                                    },
                                ),
                            )
                            primary.getOrNull(se.index)?.pre?.let { put("pre", screenBrief(it)) }
                            primary.getOrNull(se.index)?.post?.let { put("post", screenBrief(it)) }
                        }
                    },
                ),
            )
        }.toString()
    }

    /** 在证据上判定一个条件（供创作模型试写结果契约）。 */
    private fun checkContract(args: JsonObject): String {
        val runs = evidenceRuns
        if (runs.isEmpty()) return """{"error":"no evidence yet; call demonstrate first"}"""
        val cond =
            args["cond"]
                ?.let {
                    runCatching { AutopJson.default.decodeFromJsonElement(Condition.serializer(), it) }.getOrNull()
                }
                ?: return """{"error":"cond must be a flow condition object"}"""
        val indices = (args["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val primary = runs.first()
        val picked = indices?.mapNotNull { primary.getOrNull(it) } ?: primary
        return buildJsonObject {
            put("cond", describeCondition(cond))
            put(
                "steps",
                buildJsonArray {
                    picked.forEachIndexed { i, step ->
                        add(
                            buildJsonObject {
                                put("index", indices?.get(i) ?: i)
                                put("tool", step.tool)
                                put("pre", step.pre?.let { conditionHolds(cond, it) } ?: false)
                                put("post", step.post?.let { conditionHolds(cond, it) } ?: false)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun screenBrief(screen: EvidenceScreen): JsonObject =
        buildJsonObject {
            screen.app?.let { put("app", it) }
            screen.page?.let { put("page", it) }
            put("weak", screen.weak)
            put("text", screenTextOf(screen).take(SCREEN_TEXT_CHARS))
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
        const val SCREEN_TEXT_CHARS = 600
        val TERMINAL =
            setOf(
                RunStatus.SUCCEEDED,
                RunStatus.FAILED,
                RunStatus.CANCELLED,
                RunStatus.EXPIRED,
                RunStatus.INTERRUPTED,
                RunStatus.BUDGET_EXHAUSTED,
                RunStatus.BREAKER_STOPPED,
            )
        val TOOL_FILTER: Set<String> = setOf("tool.observe", "tool.memory_search")
    }
}
