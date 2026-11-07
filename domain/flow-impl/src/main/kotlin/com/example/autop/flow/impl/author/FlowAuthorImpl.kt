@file:Suppress("LongParameterList") // 创作依赖持有者

package com.example.autop.flow.impl.author

import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.ToolProbeService
import com.example.autop.agent.protocol.FallbackMode
import com.example.autop.agent.protocol.JsonParsed
import com.example.autop.agent.protocol.JsonToolProtocol
import com.example.autop.agent.protocol.RequestBuilder
import com.example.autop.agent.protocol.ResolvedCalls
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.FlowConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.flow.AuthorLive
import com.example.autop.flow.AuthorMessage
import com.example.autop.flow.AuthorState
import com.example.autop.flow.DraftInfo
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.FlowAuthor
import com.example.autop.flow.FlowAuthorSession
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowSpec
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.StoredFlow
import com.example.autop.flow.impl.db.FlowDraftEntity
import com.example.autop.flow.impl.db.FlowsDb
import com.example.autop.flow.impl.service.FlowRevisionService
import com.example.autop.llm.Block
import com.example.autop.llm.LlmException
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.LlmStreamEvent
import com.example.autop.llm.ModelRole
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolSchema
import com.example.autop.task.RunService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 创作入口依赖（DI 装配）。 */
internal class AuthorDeps(
    val db: FlowsDb,
    val gateway: LlmGateway,
    val prompts: PromptRepository,
    val configProvider: ConfigProvider,
    val validation: FlowValidationService,
    val store: FlowStore,
    val revisions: FlowRevisionService,
    val endpoints: EndpointStore,
    val threads: ThreadStore,
    val clock: Clock,
    val ids: IdGenerator,
    val invokerFactory: () -> ToolInvokerFactory,
    val probe: () -> ToolProbeService,
    val runService: () -> RunService,
)

/** AI 创作入口：草稿存 `flow_drafts`，会话是一个独立的小循环（不用 AgentSession）。 */
internal class FlowAuthorImpl(
    private val d: AuthorDeps,
) : FlowAuthor {
    private val json = AutopJson.default

    override suspend fun open(
        draftId: String?,
        editFlowId: String?,
    ): FlowAuthorSession {
        val saved = draftId?.let { d.db.flowDao().draft(it) }
        val session = FlowAuthorSessionImpl(d, saved?.draftId ?: d.ids.newId(), saved?.flowId ?: editFlowId)
        if (saved != null) {
            session.restore(
                saved.conversationJson,
                saved.draftJson?.let { json.decodeFromString(FlowDefinition.serializer(), it) },
            )
        } else if (editFlowId != null) {
            d.store.get(FlowId(editFlowId))?.let { session.seedEditing(it.definition) }
        }
        return session
    }

    override suspend fun drafts(): List<DraftInfo> =
        d.db.flowDao().drafts().map { e ->
            val name =
                e.draftJson?.let {
                    runCatching {
                        json
                            .decodeFromString(
                                FlowDefinition.serializer(),
                                it,
                            ).name
                    }.getOrNull()
                }
            DraftInfo(e.draftId, e.flowId, name.orEmpty(), e.updatedAt)
        }

    override suspend fun deleteDraft(draftId: String) = d.db.flowDao().deleteDraft(draftId)
}

/** 一个创作会话。 */
internal class FlowAuthorSessionImpl(
    private val d: AuthorDeps,
    override val draftId: String,
    private var editingFlowId: String?,
) : FlowAuthorSession {
    private val json = AutopJson.default
    private val conversation = mutableListOf<Msg>()
    private val _state = MutableStateFlow(AuthorState())
    override val state: StateFlow<AuthorState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var answerGate: CompletableDeferred<String>? = null
    private var demoGate: CompletableDeferred<Boolean>? = null
    private var cancelled = false
    private val tools = AuthorTools(d)

    /** 恢复草稿：重建对话与展示消息。 */
    fun restore(
        conversationJson: String,
        draft: FlowDefinition?,
    ) {
        conversation += json.decodeFromString(ListSerializer(Msg.serializer()), conversationJson)
        _state.update { it.copy(messages = rebuildMessages(), draft = draft) }
    }

    /** 基于已有流程修改：把现有 JSON 作为首条上下文。 */
    fun seedEditing(flow: FlowDefinition) {
        editingFlowId = flow.id
        _state.update { it.copy(draft = flow) }
        pendingDraftContext = true
    }

    private var pendingDraftContext = false

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 发送边界兜底，见下方 catch 注释
    override suspend fun send(text: String) {
        mutex.withLock {
            cancelled = false
            _state.update { it.copy(busy = true, errorCode = null, messages = it.messages + AuthorMessage.User(text)) }
            // 防御：上一轮若因取消留下未得到结果的工具调用，先补齐，否则 LLM 校验会拒绝这条用户消息。
            conversation.repairDanglingToolCalls(cancelledToolText())
            if (pendingDraftContext) {
                state.value.draft?.let { conversation += userMsg(draftContext(it)) }
                pendingDraftContext = false
            }
            conversation += userMsg(text)
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 边界兜底：模型/协议层的任何非取消异常都转为界面错误码，不让协程异常冒泡导致进程退出。
                // 错误已经体现在界面（errorCode），这里不再重复记录。
                _state.update { it.copy(errorCode = FlowErrorCodes.LLM_UNAVAILABLE) }
            } finally {
                // 取消路径上也必须完成收尾：补齐历史、结束忙碌态、保存（这些挂起调用需要不可取消的上下文）。
                withContext(NonCancellable) {
                    conversation.repairDanglingToolCalls(cancelledToolText())
                    _state.update { it.copy(busy = false, pendingQuestion = null, pendingDemonstration = null) }
                    persist()
                }
            }
        }
    }

    /** 被取消的工具调用在历史里的说明文字（模型可见，来自模板）。 */
    private fun cancelledToolText(): String = render(TOOL_CANCELLED_PROMPT, emptyMap())

    override fun answer(text: String) {
        answerGate?.complete(text)
    }

    override fun confirmDemonstration(allowed: Boolean) {
        demoGate?.complete(allowed)
    }

    override suspend fun replaceDraft(definition: FlowDefinition) {
        _state.update { it.copy(draft = definition, issues = emptyList()) }
        val issues = d.validation.validate(definition)
        _state.update { it.copy(issues = issues) }
        pendingDraftContext = true
        persist()
    }

    override fun cancel() {
        cancelled = true
        answerGate?.complete("")
        demoGate?.complete(false)
    }

    override suspend fun save(): Outcome<StoredFlow> {
        val draft =
            state.value.draft
                ?: return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "no draft to save"))
        val issues = d.validation.validate(draft)
        _state.update { it.copy(issues = issues) }
        val first = issues.firstOrNull { it.severity == IssueSeverity.ERROR }
        if (first != null) return Outcome.Err(AppError(code = first.code, message = first.message))
        // 修改既有流程时走修订路径：旧定义进版本库，并把新定义推给正在运行的实例（S3）。
        // 版本记录的原因优先取草稿自带的 reason（update_flow / patch_flow_node 的用户说明）。
        val target = editingFlowId
        val saved =
            if (target != null) {
                val draftRow = d.db.flowDao().draft(draftId)
                val reason = draftRow?.reason ?: REVISION_REASON
                d.revisions.apply(target, draft, reason)
            } else {
                d.store.save(draft)
            }
        if (saved is Outcome.Ok) d.db.flowDao().deleteDraft(draftId)
        return saved
    }

    // ---- 循环 ---------------------------------------------------------------------------------

    private suspend fun loop() {
        val config = d.configProvider.current()
        val provider = (d.gateway.forRole(ModelRole.MAIN) as? Outcome.Ok)?.value
        if (provider == null) {
            _state.update { it.copy(errorCode = FlowErrorCodes.LLM_UNAVAILABLE) }
            return
        }
        val runtime = pipeline(provider, config)
        var turn = 0
        repeat(config[FlowConfig.AUTHOR].maxTurns) {
            if (cancelled) return
            turn++
            val completed = streamTurn(runtime.provider, runtime.request(conversation)) ?: return
            if (settle(runtime, completed, turn)) return
        }
        _state.update { it.copy(errorCode = ERROR_TOO_LONG) }
    }

    /** 与智能体主循环同一套协议装配（docs/tech/04 §3.8）：能力判定、JSON 回退与请求组装。 */
    private fun pipeline(
        provider: LlmProvider,
        config: ConfigSnapshot,
    ): AuthorPipeline {
        val nativeTools = provider.profile.capabilities.tools
        // 模型不支持原生 tools 时不发 tools、改走 JSON 回退；否则模型会把 <tool_call> XML
        // 当正文返回，调用被当成普通消息展示。
        val resolver =
            FallbackMode(
                prompts = d.prompts,
                nativeTools = nativeTools,
                parseRetriesLimit = config[AgentConfig.FALLBACK].parseRetries,
            )
        val builder =
            RequestBuilder(
                prompts = d.prompts,
                fallback = !nativeTools,
                thinking = config[AgentConfig.THINKING],
                thinkingCapable = provider.profile.capabilities.thinking,
                maxOutputTokens = MAX_OUTPUT,
            )
        val schemas = tools.schemas()
        val system =
            buildList {
                add(SystemPart(render(SYSTEM, emptyMap())))
                add(SystemPart(render(SPEC, mapOf("open" to "{{", "close" to "}}"))))
                resolver.staticToolList(schemas)?.let { add(SystemPart(it)) }
            }
        return AuthorPipeline(provider, nativeTools, resolver, builder, system, schemas)
    }

    /** 处理一轮回复；返回 true 表示会话收尾（不再采样）。 */
    private suspend fun settle(
        runtime: AuthorPipeline,
        completed: LlmStreamEvent.Completed,
        turn: Int,
    ): Boolean =
        when (val resolved = runtime.resolver.resolveCalls(completed.message, turn)) {
            is ResolvedCalls.Retry -> {
                // 解析失败：保留模型原文供下轮纠正并注入重试提示，协议 JSON 不作为正文上屏。
                conversation += completed.message
                conversation += Msg(Role.USER, listOf(Block.Text(resolved.injectText)))
                publish(completed.message, showRawText = false)
                false
            }
            is ResolvedCalls.ToolCalls -> {
                conversation += completed.message
                // 回退模式下正文就是协议 JSON，不能当助手消息展示。
                publish(completed.message, showRawText = runtime.nativeTools)
                val results =
                    resolved.calls.map { call ->
                        if (call.name !in SELF_REPORTING) activity(call.name)
                        Block.ToolResult(call.id, listOf(Block.Text(runTool(call))), isError = false)
                    }
                conversation += Msg(Role.TOOL, results)
                persist()
                false
            }
            is ResolvedCalls.Done -> {
                conversation += completed.message
                if (resolved.text != null || runtime.nativeTools) {
                    publish(completed.message, displayText = resolved.text)
                } else {
                    // 回退协议重试超限仍无可用文本：不把协议 JSON 当回复，给出明确错误。
                    _state.update { it.copy(errorCode = FlowErrorCodes.LLM_INVALID_OUTPUT) }
                }
                true
            }
        }

    /** 流式采样：思考、正文与工具名实时写入 [AuthorState.live]；出错返回 null 并记错误码。 */
    private suspend fun streamTurn(
        provider: LlmProvider,
        request: LlmRequest,
    ): LlmStreamEvent.Completed? {
        var completed: LlmStreamEvent.Completed? = null
        _state.update { it.copy(live = AuthorLive()) }
        try {
            provider.stream(request).collect { event ->
                when (event) {
                    is LlmStreamEvent.ThinkingDelta -> liveUpdate { it.copy(thinking = it.thinking + event.text) }
                    is LlmStreamEvent.TextDelta -> liveUpdate { it.copy(text = it.text + event.text) }
                    is LlmStreamEvent.ToolCallStarted -> liveUpdate { it.copy(tool = event.name) }
                    is LlmStreamEvent.Completed -> completed = event
                    else -> Unit
                }
            }
        } catch (e: LlmException) {
            _state.update { it.copy(errorCode = e.error.code) }
        } finally {
            _state.update { it.copy(live = null) }
        }
        return completed
    }

    /**
     * 一轮结束：思考（折叠）与正文落成对话消息。
     * [displayText] 非空时替代模型原文（回退协议的 `final`）；[showRawText] 为 false 时
     * 正文是协议 JSON，不作为助手消息展示。
     */
    private fun publish(
        message: Msg,
        displayText: String? = null,
        showRawText: Boolean = true,
    ) {
        val thinking = thinkingOf(message)
        val text =
            when {
                displayText != null -> displayText.trim()
                showRawText ->
                    message.blocks
                        .filterIsInstance<Block.Text>()
                        .joinToString("") { it.text }
                        .trim()
                else -> ""
            }
        _state.update { s ->
            var messages = s.messages
            if (thinking.isNotBlank()) messages = messages + AuthorMessage.Thinking(thinking)
            if (text.isNotEmpty()) messages = messages + AuthorMessage.Assistant(text)
            s.copy(messages = messages)
        }
    }

    private fun liveUpdate(f: (AuthorLive) -> AuthorLive) {
        _state.update { s -> s.copy(live = s.live?.let(f)) }
    }

    private fun thinkingOf(message: Msg): String =
        message.blocks
            .filterIsInstance<Block.Thinking>()
            .joinToString("\n") { it.text }
            .trim()

    private suspend fun runTool(call: Block.ToolCall): String {
        val args =
            runCatching { json.parseToJsonElement(call.argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return when (call.name) {
            "ask_user" -> askUser(args)
            "demonstrate" -> demonstrate(args)
            "propose_flow" -> propose(args)
            else -> tools.run(call.name, args) { activity(it) }
        }
    }

    private fun activity(text: String) {
        _state.update { it.copy(messages = it.messages + AuthorMessage.Activity(text)) }
    }

    private suspend fun askUser(args: JsonObject): String {
        val question = (args["question"] as? JsonPrimitive)?.content.orEmpty()
        val options = (args["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val q = AuthorMessage.Question(question, options)
        val gate = CompletableDeferred<String>()
        answerGate = gate
        _state.update { it.copy(messages = it.messages + q, pendingQuestion = q) }
        val answer = gate.await()
        answerGate = null
        _state.update { it.copy(messages = it.messages + AuthorMessage.User(answer), pendingQuestion = null) }
        return answer
    }

    private suspend fun demonstrate(args: JsonObject): String {
        val goal = (args["goal"] as? JsonPrimitive)?.content.orEmpty()
        val gate = CompletableDeferred<Boolean>()
        demoGate = gate
        _state.update { it.copy(pendingDemonstration = goal) }
        val allowed = gate.await()
        demoGate = null
        _state.update { it.copy(pendingDemonstration = null) }
        if (!allowed) return render(DEMO_DECLINED, emptyMap())
        return tools.demonstrate(goal) { activity(it) }
    }

    private suspend fun propose(args: JsonObject): String {
        val raw = args["flow"] as? JsonObject ?: return errorJson("flow 必须是 JSON 对象")
        val definition =
            try {
                json.decodeFromJsonElement(FlowDefinition.serializer(), raw)
            } catch (e: IllegalArgumentException) {
                return errorJson(e.message.orEmpty())
            }
        val fixed =
            definition.copy(
                id =
                    editingFlowId ?: definition.id.ifBlank {
                        "flow_" + d.ids.newId()
                    },
                format = FlowSpec.FORMAT,
            )
        val issues = d.validation.validate(fixed)
        _state.update { it.copy(draft = fixed, issues = issues) }
        val ok = issues.none { it.severity == IssueSeverity.ERROR }
        persist()
        return buildJsonObject {
            put("ok", ok)
            put(
                "issues",
                buildJsonArray {
                    issues.forEach { i ->
                        add(
                            buildJsonObject {
                                put("severity", i.severity.name.lowercase())
                                i.nodeId?.let { put("node", it) }
                                put("code", i.code)
                                put("message", i.message)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun errorJson(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()

    // ---- 对话与持久化 -------------------------------------------------------------------------

    private fun userMsg(text: String) = Msg(Role.USER, listOf(Block.Text(text)))

    private fun draftContext(flow: FlowDefinition): String =
        render(DRAFT_CONTEXT, mapOf("draft" to json.encodeToString(FlowDefinition.serializer(), flow)))

    private fun render(
        id: String,
        vars: Map<String, String>,
    ): String = (d.prompts.render(id, vars) as? Outcome.Ok)?.value?.text.orEmpty()

    private suspend fun persist() {
        // REPLACE upsert 会整行覆盖：草稿自带的 reason（工具生成的修订来由）要保留到确认生效。
        val draftRow = d.db.flowDao().draft(draftId)
        d.db.flowDao().upsertDraft(
            FlowDraftEntity(
                draftId = draftId,
                flowId = editingFlowId,
                conversationJson = json.encodeToString(ListSerializer(Msg.serializer()), conversation),
                draftJson = state.value.draft?.let { json.encodeToString(FlowDefinition.serializer(), it) },
                reason = draftRow?.reason,
                updatedAt = d.clock.nowMillis(),
            ),
        )
    }

    /** 从持久化的对话重建展示消息（只还原用户与助手文字和问答）。 */
    private fun rebuildMessages(): List<AuthorMessage> =
        conversation.flatMap { m -> m.blocks.mapNotNull { b -> authorMessageOf(m, b) } }

    private fun authorMessageOf(
        m: Msg,
        b: Block,
    ): AuthorMessage? =
        when {
            m.role == Role.USER && b is Block.Text -> AuthorMessage.User(b.text)
            m.role == Role.ASSISTANT && b is Block.Thinking && b.text.isNotBlank() -> AuthorMessage.Thinking(b.text)
            // 回退模式的历史正文是协议 JSON：tool_calls 丢弃，final 只留正文。
            m.role == Role.ASSISTANT && b is Block.Text && b.text.isNotBlank() ->
                displayTextOf(b.text).takeIf { it.isNotBlank() }?.let { AuthorMessage.Assistant(it) }
            else -> null
        }

    /** 回退协议正文的展示形态：`tool_calls` 是调用指令不是助手回复；`final` 只展示正文。 */
    private fun displayTextOf(text: String): String =
        when (val parsed = JsonToolProtocol.parse(text, turn = 0)) {
            is JsonParsed.Final -> parsed.text
            is JsonParsed.Calls -> ""
            is JsonParsed.Invalid -> text
        }

    private companion object {
        /** 这些工具自己会往界面写提问/活动，不再额外加活动提示。 */
        val SELF_REPORTING = setOf("ask_user", "inspect_screen", "demonstrate")
        const val SYSTEM = "flow.author.system"
        const val SPEC = "flow.author.spec"
        const val DRAFT_CONTEXT = "flow.author.draft_context"
        const val DEMO_DECLINED = "flow.author.demo_declined"
        const val TOOL_CANCELLED_PROMPT = "flow.author.tool_cancelled"
        const val MAX_OUTPUT = 8192
        const val ERROR_TOO_LONG = "flow.author_too_long"

        /** 版本记录里的修订原因。 */
        const val REVISION_REASON = "用户确认的修订草稿"
    }
}

/** 一次创作会话的协议装配快照（能力判定在会话开始时固定）。 */
private class AuthorPipeline(
    val provider: LlmProvider,
    val nativeTools: Boolean,
    val resolver: FallbackMode,
    val builder: RequestBuilder,
    val system: List<SystemPart>,
    val schemas: List<ToolSchema>,
) {
    fun request(conversation: List<Msg>): LlmRequest = builder.build(system, conversation, schemas, finalRound = false)
}
