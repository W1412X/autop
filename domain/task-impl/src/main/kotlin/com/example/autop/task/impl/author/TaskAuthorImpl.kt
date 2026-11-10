@file:Suppress("LongParameterList") // 创作依赖持有者

package com.example.autop.task.impl.author

import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolInvokerFactory
import com.example.autop.agent.protocol.FallbackMode
import com.example.autop.agent.protocol.RequestBuilder
import com.example.autop.agent.protocol.ResolvedCalls
import com.example.autop.agent.protocol.displayTextOf
import com.example.autop.agent.protocol.sanitizeTextsOf
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.FlowConfig
import com.example.autop.core.config.UiConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.flow.AuthorLive
import com.example.autop.flow.AuthorMessage
import com.example.autop.flow.FlowStore
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
import com.example.autop.task.FilterGroup
import com.example.autop.task.FlowBinding
import com.example.autop.task.GroupMode
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.TaskAuthor
import com.example.autop.task.TaskAuthorSession
import com.example.autop.task.TaskAuthorState
import com.example.autop.task.TaskDraft
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TaskExecutor
import com.example.autop.task.TaskRepository
import com.example.autop.task.Trigger
import com.example.autop.task.TriggerSource
import com.example.autop.task.core.Cron
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 任务创作入口依赖（DI 装配）。 */
internal class TaskAuthorDeps(
    val gateway: LlmGateway,
    val prompts: PromptRepository,
    val configProvider: ConfigProvider,
    val tasks: TaskRepository,
    val flows: FlowStore,
    val sources: Set<TriggerSource>,
    val threads: ThreadStore,
    val clock: Clock,
    val ids: IdGenerator,
    val invokerFactory: () -> ToolInvokerFactory,
)

/**
 * 任务 AI 创作入口：会话是一个独立的小循环（不用 AgentSession，与流程创作
 * [com.example.autop.flow.FlowAuthor] 同构）。不落草稿库——确认即建任务。
 */
internal class TaskAuthorImpl(
    private val d: TaskAuthorDeps,
) : TaskAuthor {
    override suspend fun open(taskId: TaskId?): TaskAuthorSession {
        val base = taskId?.let { d.tasks.get(it) }
        return TaskAuthorSessionImpl(
            d = d,
            base = base,
            initialDraft = base?.let { toDraft(it) },
            missing = taskId != null && base == null,
        )
    }

    /** 既有任务 → 可编辑草稿（流程名回填，触发器还原为创作字段口径）。 */
    private suspend fun toDraft(task: Task): TaskDraft {
        var triggerType = "manual"
        var at: String? = null
        var cron: String? = null
        var timezone: String? = null
        var source: String? = null
        var condition: String? = null
        when (val trigger = task.trigger) {
            Trigger.Manual -> Unit
            is Trigger.Schedule ->
                when (val spec = trigger.spec) {
                    is ScheduleSpec.Once -> {
                        triggerType = "once"
                        at = Instant.ofEpochMilli(spec.atMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
                    }
                    is ScheduleSpec.Cron -> {
                        triggerType = "cron"
                        cron = spec.expr
                        timezone = spec.zoneId
                    }
                }
            is Trigger.Condition -> {
                triggerType = "condition"
                source = trigger.sourceId
                condition = trigger.llmCondition
            }
        }
        return TaskDraft(
            name = task.name,
            goal = task.goal,
            triggerType = triggerType,
            at = at,
            cron = cron,
            timezone = timezone,
            source = source,
            condition = condition,
            executorFlow =
                task.flow?.let {
                    d.flows
                        .get(it.flowId)
                        ?.definition
                        ?.name
                },
            needsScreen = task.needsScreen,
        )
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

/** 一个任务创作会话（[base] 非空时保存为更新）。 */
internal class TaskAuthorSessionImpl(
    private val d: TaskAuthorDeps,
    private val base: Task? = null,
    initialDraft: TaskDraft? = null,
    missing: Boolean = false,
) : TaskAuthorSession {
    private val json = AutopJson.default
    private val conversation = mutableListOf<Msg>()
    private val _state =
        MutableStateFlow(
            TaskAuthorState(
                draft = initialDraft,
                editing = base != null,
                errorCode = if (missing) TaskErrorCodes.NOT_FOUND else null,
            ),
        )
    override val state: StateFlow<TaskAuthorState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var answerGate: CompletableDeferred<String>? = null
    private var cancelled = false
    private val tools = TaskAuthorTools(d.invokerFactory, d.configProvider, d.prompts, d.ids, d.threads)
    private val sourceIds: Set<String> = d.sources.map { it.id }.toSet()

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 发送边界兜底，见下方 catch 注释
    override suspend fun send(text: String) {
        mutex.withLock {
            cancelled = false
            _state.update { it.copy(busy = true, errorCode = null, messages = it.messages + AuthorMessage.User(text)) }
            // 防御：上一轮若因取消留下未得到结果的工具调用，先补齐，否则 LLM 校验会拒绝这条用户消息。
            conversation.repairDanglingToolCalls(cancelledToolText())
            conversation += userMsg(text)
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 边界兜底：模型/协议层的任何非取消异常都转为界面错误码，不让协程异常冒泡导致进程退出。
                _state.update { it.copy(errorCode = ERROR_LLM_UNAVAILABLE) }
            } finally {
                // 取消路径上也必须完成收尾：补齐历史、结束忙碌态（挂起调用需要不可取消的上下文）。
                withContext(NonCancellable) {
                    conversation.repairDanglingToolCalls(cancelledToolText())
                    _state.update { it.copy(busy = false, pendingQuestion = null) }
                }
            }
        }
    }

    override fun answer(text: String) {
        answerGate?.complete(text)
    }

    override fun cancel() {
        cancelled = true
        answerGate?.complete("")
    }

    override suspend fun save(): Outcome<Task> {
        val draft =
            state.value.draft
                ?: return Outcome.Err(AppError(code = TaskErrorCodes.INVALID, message = "no draft to save"))
        val task =
            when (val built = assemble(draft, base)) {
                is Outcome.Ok -> built.value
                is Outcome.Err -> return built
            }
        return d.tasks.save(task)
    }

    // ---- 循环 ---------------------------------------------------------------------------------

    private suspend fun loop() {
        val config = d.configProvider.current()
        val provider = (d.gateway.forRole(ModelRole.MAIN) as? Outcome.Ok)?.value
        if (provider == null) {
            _state.update { it.copy(errorCode = ERROR_LLM_UNAVAILABLE) }
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

    /** 与智能体主循环同一套协议装配：能力判定、JSON 回退与请求组装（含输出语言要求）。 */
    private fun pipeline(
        provider: LlmProvider,
        config: ConfigSnapshot,
    ): TaskPipeline {
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
                profile = provider.profile,
                thinking = config[AgentConfig.THINKING],
                texts = sanitizeTextsOf(d.prompts),
                maxOutputTokensOverride = MAX_OUTPUT,
            )
        val schemas = tools.schemas()
        val system =
            buildList {
                add(SystemPart(render(SYSTEM, emptyMap())))
                add(SystemPart(render(OUTPUT, mapOf("lang" to languageLabel()))))
                add(SystemPart(render(SPEC, emptyMap())))
                base?.let { task ->
                    val taskJson = json.encodeToString(Task.serializer(), task)
                    add(SystemPart(render(EDIT_CONTEXT, mapOf("task" to taskJson))))
                }
                resolver.staticToolList(schemas)?.let { add(SystemPart(it)) }
            }
        return TaskPipeline(provider, nativeTools, resolver, builder, system, schemas)
    }

    /** 处理一轮回复；返回 true 表示会话收尾（不再采样）。 */
    private suspend fun settle(
        runtime: TaskPipeline,
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
                false
            }
            is ResolvedCalls.Done -> {
                conversation += completed.message
                if (resolved.text != null || runtime.nativeTools) {
                    publish(completed.message, displayText = resolved.text)
                } else {
                    // 回退协议重试超限仍无可用文本：不把协议 JSON 当回复，给出明确错误。
                    _state.update { it.copy(errorCode = ERROR_INVALID_OUTPUT) }
                }
                true
            }
        }

    /** 流式采样：思考、正文与工具名实时写入 [TaskAuthorState.live]；出错返回 null 并记错误码。 */
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
                    displayTextOf(
                        message.blocks
                            .filterIsInstance<Block.Text>()
                            .joinToString("") { it.text },
                    ).trim()
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
            "propose_task" -> propose(args)
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

    /** 组装草稿并把字段问题反馈给模型（草稿总是写入状态，用户可见）。 */
    private fun propose(args: JsonObject): String {
        val draft = tools.propose(args)
        val issues = draftIssues(draft)
        _state.update { it.copy(draft = draft) }
        return buildJsonObject {
            put("ok", issues.isEmpty())
            put(
                "issues",
                buildJsonArray {
                    issues.forEach { add(JsonPrimitive(it)) }
                },
            )
        }.toString()
    }

    // ---- 草稿装配 -----------------------------------------------------------------------------

    /** 校验 [TaskDraft] 字段（propose_task 的即时反馈；装配期还会再走 TaskRepository 校验）。 */
    private fun draftIssues(draft: TaskDraft): List<String> =
        buildList {
            if (draft.name.isBlank()) add("name 为空")
            if (draft.goal.isBlank()) add("goal 为空")
            when (draft.triggerType.trim()) {
                "manual" -> Unit
                "once" -> {
                    val at = parseAt(draft.at)
                    when {
                        at == null -> add("at 不可解析（yyyy-MM-dd HH:mm 或 ISO-8601）")
                        at <= d.clock.nowMillis() -> add("at 早于当前时间，到点执行需要将来的时刻")
                    }
                }
                "cron" -> {
                    val expr = draft.cron?.trim().orEmpty()
                    if (!cronValid(expr)) add("cron 不合法（5 段表达式：分 时 日 月 周）")
                }
                "condition" -> {
                    val source = draft.source?.trim().orEmpty()
                    if (source.isEmpty()) {
                        add("condition 需要 source 事件源")
                    } else if (source !in sourceIds) {
                        add("未知事件源：$source")
                    }
                    if (draft.condition?.trim().isNullOrEmpty()) add("condition 需要判定条件")
                }
                else -> add("triggerType 未知：${draft.triggerType}")
            }
        }

    /** 草稿 → 待保存 [Task]（触发器解析 + 流程绑定）；[base] 非空时只覆盖可编辑字段。 */
    private suspend fun assemble(
        draft: TaskDraft,
        base: Task?,
    ): Outcome<Task> {
        val trigger = buildTrigger(draft, base?.trigger)
        if (trigger == null) {
            return Outcome.Err(
                AppError(code = TaskErrorCodes.INVALID, message = "trigger 无法装配（at/cron/source 不合法）"),
            )
        }
        val flowName = draft.executorFlow?.trim().orEmpty()
        val flow = if (flowName.isEmpty()) null else d.flows.resolve(flowName)
        if (flowName.isNotEmpty() && flow == null) {
            return Outcome.Err(AppError(code = TaskErrorCodes.INVALID, message = "找不到流程：$flowName"))
        }
        val now = d.clock.nowMillis()
        val edited =
            base?.copy(
                name = draft.name.trim(),
                goal = goalOf(draft),
                trigger = trigger,
                needsScreen = draft.needsScreen,
                executor = if (flow != null) TaskExecutor.FLOW else TaskExecutor.AGENT,
                flow = flow?.let { FlowBinding(FlowId(it.definition.id)) },
                updatedAt = now,
            )
        return Outcome.Ok(
            edited ?: Task(
                id = TaskId(d.ids.newId()),
                name = draft.name.trim(),
                goal = goalOf(draft),
                trigger = trigger,
                needsScreen = draft.needsScreen,
                executor = if (flow != null) TaskExecutor.FLOW else TaskExecutor.AGENT,
                flow = flow?.let { FlowBinding(FlowId(it.definition.id)) },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** goal 交付：对话中沉淀的提示一并写进目标（任务以"目标 + 描述"存在）。 */
    private fun goalOf(draft: TaskDraft): String {
        val hint = draft.hint.trim()
        return if (hint.isEmpty()) draft.goal.trim() else draft.goal.trim() + "\n" + hint
    }

    private fun buildTrigger(
        draft: TaskDraft,
        base: Trigger?,
    ): Trigger? =
        when (draft.triggerType.trim()) {
            "manual" -> Trigger.Manual
            "once" ->
                parseAt(draft.at)
                    ?.takeIf { it > d.clock.nowMillis() }
                    ?.let { Trigger.Schedule(ScheduleSpec.Once(it)) }
            "cron" -> {
                val expr = draft.cron?.trim().orEmpty()
                if (!cronValid(expr)) {
                    null
                } else {
                    val timezone = draft.timezone?.trim().orEmpty()
                    val zone = if (timezone.isEmpty()) ZoneId.systemDefault().id else timezone
                    Trigger.Schedule(ScheduleSpec.Cron(expr, zone))
                }
            }
            "condition" -> conditionTrigger(draft, base)
            else -> null
        }

    /** 条件触发：同一事件源沿用原冷却与每日上限，避免编辑时被默认值覆盖。 */
    private fun conditionTrigger(
        draft: TaskDraft,
        base: Trigger?,
    ): Trigger? {
        val source = draft.source?.trim().orEmpty()
        val condition = draft.condition?.trim().orEmpty()
        if (source.isEmpty() || condition.isEmpty() || source !in sourceIds) return null
        val previous = base as? Trigger.Condition
        return Trigger.Condition(
            sourceId = source,
            filter = previous?.takeIf { it.sourceId == source }?.filter ?: FilterGroup(GroupMode.ALL, emptyList()),
            llmCondition = condition,
            cooldownMs = previous?.takeIf { it.sourceId == source }?.cooldownMs ?: DEFAULT_COOLDOWN_MS,
            dailyLimit = previous?.takeIf { it.sourceId == source }?.dailyLimit ?: DEFAULT_DAILY_LIMIT,
        )
    }

    private fun cronValid(expr: String): Boolean = expr.isNotEmpty() && runCatching { Cron.parse(expr) }.isSuccess

    /** at 时间解析：`yyyy-MM-dd HH:mm`（系统时区）或 ISO-8601。 */
    private fun parseAt(at: String?): Long? {
        val text = at?.trim().orEmpty()
        if (text.isEmpty()) return null
        val local =
            runCatching { LocalDateTime.parse(text, TIME_FORMAT) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text) }.getOrNull()
        local?.let { return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
        return runCatching { Instant.parse(text) }.getOrNull()?.toEpochMilli()
    }

    // ---- 对话辅助 -----------------------------------------------------------------------------

    private fun userMsg(text: String) = Msg(Role.USER, listOf(Block.Text(text)))

    private fun cancelledToolText(): String = render(TOOL_CANCELLED, emptyMap())

    private fun languageLabel(): String =
        when (d.configProvider.current()[UiConfig.APPEARANCE].language) {
            LANG_EN -> "English"
            LANG_ZH -> "中文"
            else -> if (Locale.getDefault().language == LANG_EN) "English" else "中文"
        }

    private fun render(
        id: String,
        vars: Map<String, String>,
    ): String = (d.prompts.render(id, vars) as? Outcome.Ok)?.value?.text.orEmpty()

    private companion object {
        /** 这些工具自己会往界面写提问/活动，不再额外加活动提示。 */
        val SELF_REPORTING = setOf("ask_user", "inspect_screen")
        const val SYSTEM = "task.author.system"
        const val SPEC = "task.author.spec"
        const val EDIT_CONTEXT = "task.author.edit_context"
        const val OUTPUT = "agent.system.output"
        const val MAX_OUTPUT = 8192
        const val ERROR_TOO_LONG = "task.author_too_long"
        const val ERROR_LLM_UNAVAILABLE = "task.author_llm_unavailable"
        const val ERROR_INVALID_OUTPUT = "task.author_invalid_output"

        /** 条件触发默认值（与任务编辑器一致）。 */
        const val DEFAULT_COOLDOWN_MS = 600_000L
        const val DEFAULT_DAILY_LIMIT = 20
        const val LANG_EN = "en"
        const val LANG_ZH = "zh"

        /** 取消工具调用的通用说明（内容与域无关，复用流程创作的模板）。 */
        const val TOOL_CANCELLED = "flow.author.tool_cancelled"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

/** 一次创作会话的协议装配快照（能力判定在会话开始时固定）。 */
private class TaskPipeline(
    val provider: LlmProvider,
    val nativeTools: Boolean,
    val resolver: FallbackMode,
    val builder: RequestBuilder,
    val system: List<SystemPart>,
    val schemas: List<ToolSchema>,
) {
    fun request(conversation: List<Msg>): LlmRequest = builder.build(system, conversation, schemas, finalRound = false)
}

/**
 * 取消后补齐未得到结果的工具调用（与 flow-impl 的 ConversationRepair 同构）：
 * 助手消息里的工具调用若没有对应结果，下一条用户消息会被 LLM 校验拒绝。
 */
private fun MutableList<Msg>.repairDanglingToolCalls(cancelledText: String): Boolean {
    val last = lastOrNull() ?: return false
    if (last.role != Role.ASSISTANT) return false
    val calls = last.blocks.filterIsInstance<Block.ToolCall>()
    if (calls.isEmpty()) return false
    val results = calls.map { call -> Block.ToolResult(call.id, listOf(Block.Text(cancelledText)), isError = true) }
    add(Msg(Role.TOOL, results))
    return true
}
