/**
 * 智能体侧任务工具（docs/tech/07 §7）：`list_tasks` 查看任务清单，`create_task` 创建任务，
 * `update_task` 修改任务。删除只能由用户在任务页操作，不提供工具。
 *
 * 结果 `data.links` 供对话页渲染快捷入口（任务详情 / 手动编辑 / AI 编辑）。
 */
package com.example.autop.task.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.agent.ToolSpec
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.flow.FlowStore
import com.example.autop.llm.Block
import com.example.autop.task.FilterGroup
import com.example.autop.task.FilterJson
import com.example.autop.task.FlowBinding
import com.example.autop.task.GroupMode
import com.example.autop.task.ScheduleSpec
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TaskExecutor
import com.example.autop.task.TaskRepository
import com.example.autop.task.Trigger
import com.example.autop.task.TriggerSource
import com.example.autop.task.core.Cron
import com.example.autop.task.core.FilterValidator
import com.example.autop.task.impl.flowsEnabled
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import javax.inject.Inject
import javax.inject.Singleton

/** 列出任务。 */
@Singleton
public class ListTasksTool
    @Inject
    internal constructor(
        private val tasks: TaskRepository,
    ) : Tool {
        override val id: String = "tool.list_tasks"
        override val spec: ToolSpec = TaskToolSpecs.listTasks
        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val all = tasks.observeTasks().first().sortedByDescending { it.updatedAt }
            val flowsOn = ctx.config.flowsEnabled()
            val list = buildJsonArray { all.forEach { add(taskSummary(it, flowsOn)) } }
            // 快捷入口只给最近修改的几条，避免任务多时对话里铺满 chip。
            val links =
                buildJsonArray {
                    all.take(MAX_LINK_COUNT).forEach { add(link("task_detail", it.id.value, label = it.name)) }
                }
            return ToolOutput(
                ok = true,
                content = listOf(Block.Text(list.toString())),
                data =
                    buildJsonObject {
                        put("tasks", list)
                        put("links", links)
                    },
            )
        }
    }

/** 创建任务。 */
@Singleton
public class CreateTaskTool
    @Inject
    internal constructor(
        private val tasks: TaskRepository,
        private val flows: FlowStore,
        private val sources: Set<@JvmSuppressWildcards TriggerSource>,
        private val clock: Clock,
        private val ids: IdGenerator,
    ) : Tool {
        override val id: String = "tool.create_task"
        override val spec: ToolSpec = TaskToolSpecs.createTask()
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override fun specFor(ctx: ToolContext): ToolSpec =
            TaskToolSpecs.createTask(flowsEnabled = ctx.config.flowsEnabled())

        /** 解析触发器与流程绑定；任一项非法即返回对应错误。 */
        private suspend fun bindings(args: JsonObject): Outcome<Pair<Trigger, FlowBinding?>> {
            val trigger =
                when (val built = TaskArgs.trigger(args, sources)) {
                    is Outcome.Ok -> built.value
                    is Outcome.Err -> return built
                }
            val flow =
                when (val resolved = TaskArgs.flow(args, parent = null, flows = flows)) {
                    is Outcome.Ok -> resolved.value
                    is Outcome.Err -> return resolved
                }
            return Outcome.Ok(trigger to flow)
        }

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val name = args.text("name")?.trim().orEmpty()
            val goal = args.text("goal")?.trim().orEmpty()
            if (name.isEmpty() || goal.isEmpty()) {
                return failure(TaskErrorCodes.INVALID, "name 与 goal 必填", "用一句话写清任务名与要做什么")
            }
            if (!ctx.config.flowsEnabled() && args.hasFlowArg() && !args.text("flow").isNullOrBlank()) {
                return flowDisabledFailure()
            }
            val (trigger, flow) =
                when (val bound = bindings(args)) {
                    is Outcome.Ok -> bound.value
                    is Outcome.Err -> return failure(bound.error.code, bound.error.message, bound.error.hint)
                }
            val now = clock.nowMillis()
            val task =
                Task(
                    id = TaskId(ids.newId()),
                    name = name,
                    goal = goal,
                    trigger = trigger,
                    needsScreen = args.bool("needs_screen") ?: true,
                    backgroundScreen = args.bool("background_screen") ?: false,
                    enabled = args.bool("enabled") ?: true,
                    executor = if (flow != null) TaskExecutor.FLOW else TaskExecutor.AGENT,
                    flow = flow,
                    createdAt = now,
                    updatedAt = now,
                )
            return save(tasks, task)
        }
    }

/** 修改任务（按 id 或名称定位；不提供删除）。 */
@Singleton
public class UpdateTaskTool
    @Inject
    internal constructor(
        private val tasks: TaskRepository,
        private val flows: FlowStore,
        private val sources: Set<@JvmSuppressWildcards TriggerSource>,
        private val clock: Clock,
    ) : Tool {
        override val id: String = "tool.update_task"
        override val spec: ToolSpec = TaskToolSpecs.updateTask()
        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override fun specFor(ctx: ToolContext): ToolSpec =
            TaskToolSpecs.updateTask(flowsEnabled = ctx.config.flowsEnabled())

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            val reference = args.text("task")?.trim().orEmpty()
            if (reference.isEmpty()) return failure(TaskErrorCodes.INVALID, "需要 task 参数", "传任务 id 或任务名")
            val current =
                tasks.observeTasks().first().firstOrNull {
                    it.id.value == reference || it.name.equals(reference, ignoreCase = true)
                } ?: return failure(TaskErrorCodes.NOT_FOUND, "找不到任务：$reference", "先调用 list_tasks 查看任务")
            // 流程关闭时任何 flow 参数（含解绑的空串）都拒绝，保留既有绑定（ADR-0030）。
            if (!ctx.config.flowsEnabled() && args.hasFlowArg()) return flowDisabledFailure()
            return patch(args, current)
        }

        /**
         * 修改触发器。只传 source 或 condition 而没有 trigger_type 时，旧实现会静默丢弃并返回成功。
         * 现在：当前任务是条件触发，就沿用原事件源并生效；否则直接报错。
         */
        private fun triggerFor(
            args: JsonObject,
            current: Task,
        ): Outcome<Trigger> {
            if (args.text("trigger_type") != null) return TaskArgs.trigger(args, sources, base = current.trigger)
            val touchesCondition = listOf("source", "filter", "condition").any { args.text(it) != null }
            if (!touchesCondition) return Outcome.Ok(current.trigger)
            val previous =
                current.trigger as? Trigger.Condition
                    ?: return Outcome.Err(
                        AppError(
                            code = TaskErrorCodes.INVALID,
                            message = "当前任务不是条件触发，不能只修改 source、filter 或 condition",
                            hint = "同时传 trigger_type=condition 才能把任务切换为条件触发",
                        ),
                    )
            val merged =
                JsonObject(
                    buildMap<String, JsonElement> {
                        put("trigger_type", JsonPrimitive("condition"))
                        put("source", JsonPrimitive(previous.sourceId))
                        putAll(args)
                    },
                )
            return TaskArgs.trigger(merged, sources, base = previous)
        }

        private suspend fun patch(
            args: JsonObject,
            current: Task,
        ): ToolOutput {
            val trigger =
                when (val built = triggerFor(args, current)) {
                    is Outcome.Ok -> built.value
                    is Outcome.Err -> return failure(built.error.code, built.error.message, built.error.hint)
                }
            val flow =
                when (val resolved = TaskArgs.flow(args, parent = current.flow, flows = flows)) {
                    is Outcome.Ok -> resolved.value
                    is Outcome.Err -> return failure(resolved.error.code, resolved.error.message, resolved.error.hint)
                }
            val updated =
                current.copy(
                    name = args.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
                    goal = args.text("goal")?.trim()?.takeIf { it.isNotEmpty() } ?: current.goal,
                    trigger = trigger,
                    needsScreen = args.bool("needs_screen") ?: current.needsScreen,
                    backgroundScreen = args.bool("background_screen") ?: current.backgroundScreen,
                    replayScript =
                        current.replayScript.takeIf {
                            (args.bool("background_screen") ?: current.backgroundScreen) == current.backgroundScreen
                        },
                    enabled = args.bool("enabled") ?: current.enabled,
                    executor = if (flow != null) TaskExecutor.FLOW else TaskExecutor.AGENT,
                    flow = flow,
                    updatedAt = clock.nowMillis(),
                )
            return save(tasks, updated)
        }
    }

/** 列表里单条任务的摘要。 */
private fun taskSummary(
    task: Task,
    flowsEnabled: Boolean,
): JsonObject =
    buildJsonObject {
        put("id", task.id.value)
        put("name", task.name)
        put("goal", task.goal.take(MAX_GOAL_CHARS))
        put("trigger", triggerLabel(task.trigger))
        // 流程功能关闭（ADR-0030）时不输出执行方式与流程 id，模型不会感知流程的存在。
        if (flowsEnabled) {
            put("executor", if (task.executor == TaskExecutor.FLOW) "flow" else "agent")
            task.flow?.let { put("flowId", it.flowId.value) }
        }
        put("enabled", task.enabled)
        put("updatedAt", task.updatedAt)
    }

/** 保存并返回结果与快捷入口。 */
private suspend fun save(
    tasks: TaskRepository,
    task: Task,
): ToolOutput =
    when (val saved = tasks.save(task)) {
        is Outcome.Err -> failure(saved.error.code, saved.error.message, saved.error.hint)
        is Outcome.Ok ->
            ToolOutput(
                ok = true,
                content = listOf(Block.Text("已保存任务「${task.name}」(taskId=${task.id.value})。")),
                data = taskData(task),
            )
    }

/** 任务结果的结构化数据：id + 三个快捷入口。 */
private fun taskData(task: Task): JsonObject =
    buildJsonObject {
        put("taskId", task.id.value)
        put("name", task.name)
        put("enabled", task.enabled)
        put(
            "links",
            buildJsonArray {
                add(link("task_detail", task.id.value))
                add(link("task_edit", task.id.value))
                add(link("task_ai", task.id.value))
            },
        )
    }

private fun link(
    kind: String,
    taskId: String,
    label: String? = null,
): JsonObject =
    buildJsonObject {
        put("kind", kind)
        put("taskId", taskId)
        label?.let { put("label", it) }
    }

/** 工具参数 → 触发器 / 流程绑定（创建与更新共用）。 */
private object TaskArgs {
    /** 创建时缺省 manual；更新时调用方只在给了 trigger_type 时才走这里。 */
    fun trigger(
        args: JsonObject,
        sources: Set<TriggerSource>,
        base: Trigger? = null,
    ): Outcome<Trigger> {
        val type =
            args
                .text("trigger_type")
                ?.trim()
                .orEmpty()
                .ifEmpty { "manual" }
        return when (type) {
            "manual" -> Outcome.Ok(Trigger.Manual)
            "once" -> once(args)
            "cron" -> cron(args)
            "condition" -> condition(args, sources, base)
            else -> invalid("未知触发类型：$type（manual/once/cron/condition）")
        }
    }

    private fun once(args: JsonObject): Outcome<Trigger> {
        val at =
            parseAt(args.text("at"))
                ?: return invalid("trigger_type=once 需要可解析的 at（yyyy-MM-dd HH:mm 或 ISO-8601）")
        return Outcome.Ok(Trigger.Schedule(ScheduleSpec.Once(at)))
    }

    private fun cron(args: JsonObject): Outcome<Trigger> {
        val expr = args.text("cron")?.trim().orEmpty()
        if (expr.isEmpty() || runCatching { Cron.parse(expr) }.isFailure) {
            return Outcome.Err(
                AppError(
                    code = TaskErrorCodes.CRON_INVALID,
                    message = "cron 不合法：$expr",
                    hint = "5 段表达式：分 时 日 月 周",
                ),
            )
        }
        val timezone = args.text("timezone")?.trim().orEmpty()
        return Outcome.Ok(Trigger.Schedule(ScheduleSpec.Cron(expr, timezone.ifEmpty { ZoneId.systemDefault().id })))
    }

    /**
     * 条件触发：字段过滤（filter，JSON）与语义判定（condition，自然语言）至少给一个。
     * filter 的字段必须属于事件源；同一事件源的更新沿用原过滤与原判定条件（未重新给出时）。
     */
    private fun condition(
        args: JsonObject,
        sources: Set<TriggerSource>,
        base: Trigger?,
    ): Outcome<Trigger> {
        val source = args.text("source")?.trim().orEmpty()
        val found = sources.firstOrNull { it.id == source }
        val previous = (base as? Trigger.Condition)?.takeIf { it.sourceId == source }
        return when {
            source.isEmpty() -> invalid("trigger_type=condition 需要 source（先用 list_trigger_sources 查看）")
            found == null -> unknownSource(source, sources)
            else -> conditionOf(args, found, previous)
        }
    }

    private fun unknownSource(
        source: String,
        sources: Set<TriggerSource>,
    ): Outcome<Trigger> =
        Outcome.Err(
            AppError(
                code = TaskErrorCodes.SOURCE_UNKNOWN,
                message = "未知事件源：$source",
                hint = "可用：${sources.map { it.id }.sorted().joinToString("、")}",
            ),
        )

    /** 事件源已确认存在：解析 filter（未给则沿用原值），再装配判定条件。 */
    private fun conditionOf(
        args: JsonObject,
        source: TriggerSource,
        previous: Trigger.Condition?,
    ): Outcome<Trigger> {
        val filterText = args.text("filter")?.trim().orEmpty()
        val parsed =
            if (filterText.isEmpty()) {
                Outcome.Ok(previous?.filter ?: FilterGroup(GroupMode.ALL, emptyList()))
            } else {
                parseFilter(filterText, source.fieldNames)
            }
        return when (parsed) {
            is Outcome.Err -> Outcome.Err(parsed.error)
            is Outcome.Ok -> withLlmCondition(source.id, parsed.value, args.text("condition"), previous)
        }
    }

    private fun withLlmCondition(
        sourceId: String,
        filter: FilterGroup,
        conditionText: String?,
        previous: Trigger.Condition?,
    ): Outcome<Trigger> {
        val llmCondition = conditionText?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.llmCondition
        return when {
            llmCondition == null && !FilterValidator.hasRules(filter) ->
                invalid("trigger_type=condition 需要 filter 或 condition 至少给一个")
            else ->
                Outcome.Ok(
                    Trigger.Condition(
                        sourceId = sourceId,
                        filter = filter,
                        llmCondition = llmCondition,
                        cooldownMs = previous?.cooldownMs ?: DEFAULT_COOLDOWN_MS,
                        dailyLimit = previous?.dailyLimit ?: DEFAULT_DAILY_LIMIT,
                    ),
                )
        }
    }

    /** 解析并校验 filter 文本；失败时错误信息带写法示例与可用字段。 */
    private fun parseFilter(
        text: String,
        fieldNames: List<String>,
    ): Outcome<FilterGroup> {
        val group =
            FilterJson.parse(text)
                ?: return Outcome.Err(
                    AppError(
                        code = TaskErrorCodes.INVALID,
                        message = "filter 不是合法的过滤条件 JSON",
                        hint = "写法示例：${FilterJson.EXAMPLE}",
                    ),
                )
        val problems = FilterValidator.problems(group, fieldNames)
        if (problems.isNotEmpty()) {
            return Outcome.Err(
                AppError(
                    code = TaskErrorCodes.INVALID,
                    message = problems.joinToString("；"),
                    hint = "字段名与操作符以 list_trigger_sources 的结果为准",
                ),
            )
        }
        return Outcome.Ok(group)
    }

    /** `flow` 缺省保持原绑定；空串解绑（改为智能体执行）；非空按名称解析。 */
    suspend fun flow(
        args: JsonObject,
        parent: FlowBinding?,
        flows: FlowStore,
    ): Outcome<FlowBinding?> {
        val name = args.text("flow") ?: return Outcome.Ok(parent)
        if (name.isBlank()) return Outcome.Ok(null)
        val stored =
            flows.resolve(name.trim())
                ?: return Outcome.Err(
                    AppError(code = TaskErrorCodes.INVALID, message = "找不到流程：$name", hint = "先调用 list_flows 查看可用流程"),
                )
        return Outcome.Ok(FlowBinding(FlowId(stored.definition.id)))
    }

    private fun invalid(message: String): Outcome<Trigger> =
        Outcome.Err(AppError(code = TaskErrorCodes.INVALID, message = message))

    private fun parseAt(text: String?): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        val local =
            runCatching { LocalDateTime.parse(value, TIME_FORMAT) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
        local?.let { return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
        return runCatching { Instant.parse(value) }.getOrNull()?.toEpochMilli()
    }

    val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    const val DEFAULT_COOLDOWN_MS: Long = 600_000L
    const val DEFAULT_DAILY_LIMIT: Int = 20
}

private fun triggerLabel(trigger: Trigger): String =
    when (trigger) {
        Trigger.Manual -> "manual"
        is Trigger.Schedule ->
            when (val spec = trigger.spec) {
                is ScheduleSpec.Once -> "once:${Instant.ofEpochMilli(spec.atMillis)}"
                is ScheduleSpec.Cron -> "cron:${spec.expr} ${spec.zoneId}"
            }
        is Trigger.Condition -> "condition:${trigger.sourceId}"
    }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.bool(key: String): Boolean? = text(key)?.toBooleanStrictOrNull()

/** 参数里是否给了 `flow`（JSON null 视为未给）。 */
private fun JsonObject.hasFlowArg(): Boolean = get("flow").let { it != null && it !is JsonNull }

/** 流程功能关闭时拒绝流程绑定（错误码见 [TaskErrorCodes.FLOW_DISABLED]）。 */
private fun flowDisabledFailure(): ToolOutput =
    failure(TaskErrorCodes.FLOW_DISABLED, "流程功能已关闭，不能绑定流程", "去掉 flow 参数，改由智能体按 goal 执行")

private const val MAX_GOAL_CHARS: Int = 300
private const val MAX_LINK_COUNT: Int = 5
