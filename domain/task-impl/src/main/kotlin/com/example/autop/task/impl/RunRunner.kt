/**
 * Run 执行（docs/tech/07 §4.3）：构建 RunRequest（goal/allowedTools/config 层叠/
 * runMode）、按 needsScreen 决定是否取屏幕租约、收口时校验 finish.outputs、
 * 后台通知与回到原 App。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentSession
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.ReplayConfig
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.ReplayRecorder
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.TASK_RUN_VAR
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.RunMode
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.core.config.FlowConfig
import com.example.autop.core.config.TaskConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.DisplayTarget
import com.example.autop.device.ScreenLease
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowProgressFeed
import com.example.autop.flow.FlowRunHandle
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowSession
import com.example.autop.flow.FlowStore
import com.example.autop.llm.Block
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TaskExecutor
import com.example.autop.task.TaskRepository
import com.example.autop.task.core.OutputSchemaValidator
import com.example.autop.task.impl.flowsEnabled
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** 保存流程的工具名（与 flow-impl 的 `save_as_flow` 一致）。 */
private const val SAVE_FLOW_TOOL: String = "save_as_flow"

/** Run 执行器（供 DI 装配）。 */
@Suppress("LongParameterList", "TooManyFunctions") // 执行面端口装配（DI 注入）与收口链各环节的自然结果
public class RunRunner(
    private val agentSession: AgentSession,
    private val flowSession: FlowSession,
    private val flowStore: FlowStore,
    private val flowFeed: FlowProgressFeed,
    private val runStore: RunStore,
    private val pending: PendingRuns,
    private val observer: RunObserver,
    private val touchShieldWatcher: TouchShieldWatcher,
    private val screenLease: ScreenLease,
    private val deviceState: DeviceStateProvider,
    private val appLauncher: AppLauncher,
    private val notifier: UserNotifierImpl,
    private val taskStore: TaskRepository,
    private val threadStore: ThreadStore,
    private val configProvider: ConfigProvider,
    private val prompts: PromptRepository,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    /** 执行一个已通过门条件的 Run。 */
    @Suppress("TooGenericExceptionCaught") // 统一兜底转换为 RunFinished(FAILED)，异常不外传
    public suspend fun execute(
        record: RunRecord,
        task: Task?,
    ) {
        val startedAt = clock.nowMillis()
        if (!launchRecord(record, startedAt)) return
        var terminalWritten = false
        try {
            // 启动后立即登记未知态，覆盖 pending/flow 解析与 open 挂起时的取消窗口。
            RunDisplayTargetRegistry.bindOpening(record.id)
            val entry = pending.take(record.id)
            val flowLaunch = flowLaunchOf(entry, task)
            // 兜底（调度门已先拦截）：流程功能关闭时在取屏幕租约与调用流程会话之前收口（ADR-0030）。
            if (flowLaunch != null && !configProvider.current().flowsEnabled()) {
                failFlowDisabled(record)
                return
            }
            val needsScreen = flowLaunch?.needsScreen ?: task?.needsScreen ?: true
            val execution = startAndRun(record, task, flowLaunch, entry, needsScreen)
            if (execution == null) return
            withContext(NonCancellable) {
                observer.finish(record.id, execution.finished)
                observer.clear(record.id)
                terminalWritten = true
            }
            val final = runStore.get(record.id) ?: return
            settle(
                record,
                task,
                flowLaunch,
                execution.finished,
                final,
                execution.gate?.originPackage ?: execution.originPackage,
            )
        } catch (e: CancellationException) {
            // 覆盖 pending/flow 解析、显示打开与执行窗口；终态已写后不覆盖成功/失败结果。
            if (!terminalWritten) finishCancelled(record)
            throw e
        } finally {
            withContext(NonCancellable) {
                try {
                    touchShieldWatcher.releaseDisplay(record.id)
                } catch (e: Exception) {
                    logger.w(TAG, "display_release_failed", "runId" to record.id.value, error = e)
                } finally {
                    RunDisplayTargetRegistry.clear(record.id)
                }
            }
        }
    }

    /** 打开显示并执行会话；显示失败已显式收口并返回 null，绝不回落真实屏。 */
    private suspend fun startAndRun(
        record: RunRecord,
        task: Task?,
        flowLaunch: FlowLaunch?,
        entry: PendingEntry?,
        needsScreen: Boolean,
    ): RunExecution? {
        val target =
            when (val opened = openDisplay(record.id, entry?.backgroundScreen == true)) {
                is Outcome.Ok -> opened.value
                is Outcome.Err -> {
                    failDisplay(record, opened.error)
                    return null
                }
            }
        // open 成功后立即登记，堵住取消入口在 Agent 句柄登记前误放真实屏触摸盾的窗口。
        RunDisplayTargetRegistry.bind(record.id, target)
        val displayScope =
            when (val scoped = displayScopeFor(target)) {
                is Outcome.Ok -> scoped.value
                is Outcome.Err -> {
                    failDisplay(record, scoped.error)
                    return null
                }
            }
        val request = if (flowLaunch == null && !isFlowTask(task)) buildRequest(record, task, entry) else null
        // ADR-0035：真实屏智能体 Run 按需占用屏幕；后台屏与流程运行不取真实屏租约。
        val gate = screenGateFor(record, request, target)
        val originPackage =
            if (target is DisplayTarget.Main && gate == null && needsScreen) {
                deviceState.current().foregroundPackage
            } else {
                null
            }
        val finished = runAndCatch(record, task, flowLaunch, entry, request, gate, needsScreen, target, displayScope)
        return RunExecution(finished, gate, originPackage)
    }

    /** 启动/执行窗口取消仍由既有 Observer 收口，不新建 Run 状态权威。 */
    private suspend fun finishCancelled(record: RunRecord) {
        withContext(NonCancellable) {
            observer.finish(
                record.id,
                RunFinished(status = RunOutcome.CANCELLED, summary = null, errorCode = null),
            )
            observer.clear(record.id)
        }
    }

    /** 显示打开异常按未知内部错误收口；取消继续上抛，由 finally 释放资源。 */
    @Suppress("TooGenericExceptionCaught") // 控制器契约外异常的兜底，避免 Run 停留在 RUNNING
    private suspend fun openDisplay(
        owner: RunId,
        backgroundScreen: Boolean,
    ): Outcome<DisplayTarget> =
        try {
            touchShieldWatcher.openDisplay(owner, backgroundScreen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "display_open_failed", "runId" to owner.value, error = e)
            Outcome.Err(AppError(CommonErrorCodes.UNKNOWN, "display open failed", cause = e))
        }

    /** 子 Agent/Flow 必须继承同一显示元素；冲突时显式失败，绝不覆盖为真实屏。 */
    private fun displayScopeFor(target: DisplayTarget): Outcome<CoroutineScope> {
        val inherited = scope.coroutineContext[DisplayTarget]
        if (inherited != null && inherited != target) {
            return Outcome.Err(AppError(DeviceErrorCodes.BG_RELEASED, "conflicting run display context"))
        }
        val scoped = CoroutineScope(scope.coroutineContext + target)
        return if (scoped.coroutineContext[DisplayTarget] == target) {
            Outcome.Ok(scoped)
        } else {
            Outcome.Err(AppError(DeviceErrorCodes.BG_RELEASED, "run display context not inherited"))
        }
    }

    /** 智能体 Run 的屏幕门（ADR-0035）；后台屏与非智能体 Run 为 null。 */
    private fun screenGateFor(
        record: RunRecord,
        request: RunRequest?,
        target: DisplayTarget,
    ): RunScreenGate? =
        if (target is DisplayTarget.Main) {
            request?.let {
                RunScreenGate(
                    owner = record.id.value,
                    lease = screenLease,
                    deviceState = deviceState,
                    acquireTimeoutMs = configProvider.current()[DeviceConfig.SCREEN_LEASE].acquireTimeoutMs,
                )
            }
        } else {
            null
        }

    /** 执行 Run 并把异常统一收口为 [RunFinished]；取消照常上抛（收口后）。 */
    @Suppress("TooGenericExceptionCaught") // 统一兜底转换为 RunFinished(FAILED)，异常不外传
    private suspend fun runAndCatch(
        record: RunRecord,
        task: Task?,
        flowLaunch: FlowLaunch?,
        entry: PendingEntry?,
        request: RunRequest?,
        gate: RunScreenGate?,
        needsScreen: Boolean,
        target: DisplayTarget,
        displayScope: CoroutineScope,
    ): RunFinished =
        try {
            val run = request?.copy(screenGate = gate)
            if (target is DisplayTarget.Main && gate == null && needsScreen) {
                screenLease.withLease(record.id.value) {
                    runBlock(record, run, flowLaunch, task, entry, target, displayScope)
                }
            } else {
                runBlock(record, run, flowLaunch, task, entry, target, displayScope)
            }
        } catch (e: CancellationException) {
            // 由 execute 的统一启动窗口收口；此处只释放屏幕门并继续上抛。
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "run_crashed", "runId" to record.id.value, error = e)
            val failed =
                RunFinished(
                    status = RunOutcome.FAILED,
                    summary = null,
                    errorCode = CommonErrorCodes.UNKNOWN,
                    liveNote = prompts.runEndedNote(RunOutcome.FAILED),
                )
            ensureRunFinished(record, failed)
            failed
        } finally {
            gate?.release()
        }

    /** 收口后的沉淀与通知：回放脚本、流程建议、后台通知、回到原 App。 */
    private suspend fun settle(
        record: RunRecord,
        task: Task?,
        flowLaunch: FlowLaunch?,
        finished: RunFinished,
        final: RunRecord,
        originPkg: String?,
    ) {
        if (validateOutputs(task, finished, final) && !isFlowRun(flowLaunch, task)) {
            val events = threadStore.events(record.threadId).filter { it.runId == record.id }
            recordReplayScript(task, record, finished, events)
            suggestFlowIfRepeatable(record, finished, events)
        }
        notifyIfBackground(final)
        returnToOrigin(task, originPkg)
    }

    /**
     * 条件置启动（取消幂等收口）：已被收口（取消/过期）的 Run 不得复活——启动与取消在
     * runStore 原子 mutate 上仲裁，恰有一方生效；未启动则消费取消意图并返回 false。
     */
    private suspend fun launchRecord(
        record: RunRecord,
        startedAt: Long,
    ): Boolean {
        var launched = false
        runStore.mutate(record.id) {
            if (it.status in RunStatus.CANCELABLE) {
                launched = true
                it.copy(status = RunStatus.RUNNING, startedAt = startedAt)
            } else {
                it
            }
        }
        if (!launched) {
            pending.consumeCancelled(record.id)
            logger.d(TAG, "run_closed_before_start", "runId" to record.id.value)
        }
        return launched
    }

    private fun isFlowTask(task: Task?): Boolean = task?.executor == TaskExecutor.FLOW && task.flow != null

    /** 流程功能关闭（ADR-0030）：流程运行不占屏幕、不调用流程会话，直接以 `task.flow_disabled` 收口。 */
    private suspend fun failFlowDisabled(record: RunRecord) {
        val finished =
            RunFinished(
                status = RunOutcome.FAILED,
                summary = null,
                errorCode = TaskErrorCodes.FLOW_DISABLED,
                liveNote = prompts.runEndedNote(RunOutcome.FAILED),
            )
        ensureRunFinished(record, finished)
        observer.finish(record.id, finished)
        observer.clear(record.id)
        runStore.get(record.id)?.let { notifyIfBackground(it) }
    }

    /** 显示目标打开/继承失败：保留控制器错误码并显式收口，不调用 Agent，也不回落真实屏。 */
    private suspend fun failDisplay(
        record: RunRecord,
        error: AppError,
    ) {
        val finished =
            RunFinished(
                status = RunOutcome.FAILED,
                summary = null,
                errorCode = error.code,
                liveNote = prompts.runEndedNote(RunOutcome.FAILED),
            )
        ensureRunFinished(record, finished)
        observer.finish(record.id, finished)
        observer.clear(record.id)
        runStore.get(record.id)?.let { notifyIfBackground(it) }
        logger.w(TAG, "display_open_failed", "runId" to record.id.value, "code" to error.code)
    }

    private fun isFlowRun(
        launch: FlowLaunch?,
        task: Task?,
    ): Boolean = launch != null || isFlowTask(task)

    /** 独立流程运行取暂存的启动数据；任务绑定的流程在此按 id 取最新定义。 */
    private suspend fun flowLaunchOf(
        entry: PendingEntry?,
        task: Task?,
    ): FlowLaunch? {
        entry?.flow?.let { return it }
        val binding = task?.flow?.takeIf { task.executor == TaskExecutor.FLOW } ?: return null
        val stored = flowStore.get(binding.flowId) ?: return null
        return FlowLaunch(stored.definition, binding.params, FlowRunOptions(), task.needsScreen)
    }

    private suspend fun runBlock(
        record: RunRecord,
        request: RunRequest?,
        flowLaunch: FlowLaunch?,
        task: Task?,
        entry: PendingEntry?,
        target: DisplayTarget,
        displayScope: CoroutineScope,
    ): RunFinished {
        // N217：仅真实屏新 Run 在启动前同步清除盾抑制；后台屏不调用触摸盾启动/watch。
        if (target is DisplayTarget.Main) touchShieldWatcher.beginRun(record.id, target)
        val handle =
            when {
                flowLaunch != null -> flowSession.start(flowRequest(record, task, entry, flowLaunch), displayScope)
                request != null -> agentSession.start(request, displayScope)
                else ->
                    return RunFinished(
                        status = RunOutcome.FAILED,
                        summary = null,
                        errorCode = FlowErrorCodes.NOT_FOUND,
                    )
            }
        observer.begin(record, handle)
        // 取消意图补投（取消幂等收口）：句柄登记前窗口落下的取消在此兑现。
        if (pending.consumeCancelled(record.id)) {
            logger.d(TAG, "cancel_delivered_after_begin", "runId" to record.id.value)
            handle.cancel()
        }
        // 流程运行登记到根 Run（进度互通 P1-2；进度窗可"只停流程" P1-3）。
        if (flowLaunch != null && handle is FlowRunHandle) {
            flowFeed.register(record.id, flowLaunch.definition.name, handle)
        }
        val watcher = scope.launch { observer.watch(record.id, handle) }
        val shieldWatcher =
            if (target is DisplayTarget.Main) {
                scope.launch { touchShieldWatcher.watch(handle, record.id.value, screenLease.holder) }
            } else {
                null
            }
        try {
            return handle.await()
        } finally {
            watcher.cancelAndJoin()
            shieldWatcher?.cancelAndJoin()
        }
    }

    private fun flowRequest(
        record: RunRecord,
        task: Task?,
        entry: PendingEntry?,
        launch: FlowLaunch,
    ): FlowRunRequest =
        FlowRunRequest(
            runId = record.id,
            threadId = record.threadId,
            flow = launch.definition,
            params = launch.params,
            trigger = triggerObject(entry?.vars.orEmpty()),
            taskId = task?.id,
            origin = record.origin.name.lowercase(),
            options = launch.options,
            config = configFor(task),
        )

    /** 	rigger. 前缀的变量 → 流程的 	rigger 命名空间；extracted. 子前缀收成子对象。 */
    private fun triggerObject(vars: Map<String, String>): JsonObject {
        val top = LinkedHashMap<String, JsonElement>()
        val extracted = LinkedHashMap<String, JsonElement>()
        for ((key, value) in vars) {
            if (!key.startsWith(TRIGGER_PREFIX)) continue
            val name = key.removePrefix(TRIGGER_PREFIX)
            if (name.startsWith(EXTRACTED_PREFIX)) {
                extracted[name.removePrefix(EXTRACTED_PREFIX)] = JsonPrimitive(value)
            } else {
                top[name] = JsonPrimitive(value)
            }
        }
        if (extracted.isNotEmpty()) top[EXTRACTED_KEY] = JsonObject(extracted)
        return JsonObject(top)
    }

    private suspend fun buildRequest(
        record: RunRecord,
        task: Task?,
        entry: PendingEntry?,
    ): RunRequest {
        val config = configFor(task)
        val tier = effectiveTier(entry, task, config)
        return RunRequest(
            runId = record.id,
            threadId = record.threadId,
            // 输入已在入队时落盘（WP8.1）：这里必须传空，TurnLoop 不再重复写 INPUT（注意事项 9）。
            input = emptyList(),
            goal = task?.goal,
            // 运行来源与运行模式档位分别用独立的变量键（ADR-0040）；档位同时放进变量，供 state.run_mode / state.shell 读取。
            vars =
                (entry?.vars ?: emptyMap()) +
                    mapOf(
                        RUN_ORIGIN_KEY to runOrigin(record.origin),
                        RUN_MODE_VAR to tier.id,
                    ) +
                    (if (task != null) mapOf(TASK_RUN_VAR to "true") else emptyMap()),
            config = config,
            toolFilter = task?.allowedTools,
            // 任务运行不能再创建或修改任务（否则会把目标当成新请求，建出重复任务）。
            deniedTools = if (task != null) TASK_RUN_DENIED_TOOLS else emptySet(),
            modelProfileId = threadStore.modelProfileOf(record.threadId),
            replay = replayPlan(record, task),
            runMode = tier,
        )
    }

    /**
     * 本次 Run 的运行模式（ADR-0040 §2.6）：发送侧指定 > 任务设置 > 标准模式。
     * 完全模式需要 Shell 执行总闸开启，否则降为标准模式。
     */
    private fun effectiveTier(
        entry: PendingEntry?,
        task: Task?,
        config: ConfigSnapshot,
    ): RunMode {
        val requested = RunMode.fromId(entry?.vars?.get(RUN_MODE_VAR)) ?: task?.runMode ?: RunMode.STANDARD
        val shellAllowed = config[FeaturesConfig.FEATURES].shellExec
        return if (requested == RunMode.FULL && !shellAllowed) RunMode.STANDARD else requested
    }

    /**
     * 崩溃兜底（WP8.2）：线程里本 Run 的最后一条事件还不是 RunFinished 时补写一条
     * （带 liveNote），保证后续 Run 的模型能看到上一个任务没有完成。
     */
    @Suppress("TooGenericExceptionCaught") // 补写失败只记日志，不再外传（原异常已记过）
    private suspend fun ensureRunFinished(
        record: RunRecord,
        finished: RunFinished,
    ) {
        try {
            val last = threadStore.events(record.threadId).lastOrNull { it.runId == record.id }
            if (last?.payload is RunFinished) return
            threadStore.append(record.threadId, record.id, finished)
        } catch (e: Exception) {
            logger.w(TAG, "append_crash_finish_failed", "runId" to record.id.value, error = e)
        }
    }

    /** 定时/条件触发且有脚本时优先回放（docs/tech/04 §3.12，R1/A6）。 */
    @Suppress("ReturnCount") // 回放资格逐条早退是选择链的自然结构
    private fun replayPlan(
        record: RunRecord,
        task: Task?,
    ): ReplayPlan? {
        val script = task?.replayScript ?: return null
        if (!task.enabled) return null
        if (record.origin != RunOrigin.SCHEDULE && record.origin != RunOrigin.CONDITION) return null
        val config = configProvider.current()[TaskConfig.REPLAY]
        if (!config.enabled) return null
        return ReplayPlan(
            script = script,
            config =
                ReplayConfig(
                    preStateWaitMs = config.preStateWaitMs,
                    pollMs = config.pollMs,
                    threshold = config.threshold,
                    keysWeight = config.keysWeight,
                    appWeight = config.appWeight,
                ),
        )
    }

    private fun runOrigin(origin: RunOrigin): String =
        if (origin == RunOrigin.CHAT) RUN_ORIGIN_FOREGROUND else RUN_ORIGIN_BACKGROUND

    /** Task 覆盖层 + 策略覆盖层叠（docs/tech/07 §4.3）。 */
    private fun configFor(task: Task?) =
        if (task == null) {
            configProvider.layered()
        } else {
            val overlay =
                buildMap {
                    putAll(task.configOverrides)
                    put(KEY_POLICY, policyObject(task))
                }
            configProvider.layered(JsonObject(overlay))
        }

    private fun policyObject(task: Task): JsonObject {
        val encoded = AutopJson.default.encodeToString(task.policyOverrides)
        return AutopJson.default.decodeFromString(JsonObject.serializer(), encoded)
    }

    /** finish.outputs 按 Task.outputSchema 校验（R1/C2）：不通过记失败。 */
    @Suppress("ReturnCount") // 无 schema/无 outputs 时免校验早退
    private suspend fun validateOutputs(
        task: Task?,
        finished: RunFinished,
        record: RunRecord,
    ): Boolean {
        val schema = task?.outputSchema ?: return true
        val outputs = finished.outputs ?: return true
        val problems = OutputSchemaValidator.validate(outputs, schema)
        if (problems.isNotEmpty()) {
            logger.w(TAG, "output_schema_invalid", "runId" to record.id.value, "problems" to problems.size)
            runStore.mutate(record.id) {
                it.copy(status = RunStatus.FAILED, errorCode = TaskErrorCodes.INVALID)
            }
        }
        return problems.isEmpty()
    }

    /**
     * 成功且界面操作足够多、本次没有保存过流程时，提示用户把这次操作保存为流程
     * （docs/design/flows-recording-recovery.md A5）。阈值来自 `flow.save.suggestMinSteps`。
     */
    private fun suggestFlowIfRepeatable(
        record: RunRecord,
        finished: RunFinished,
        events: List<ThreadEvent>,
    ) {
        if (finished.status != RunOutcome.SUCCEEDED) return
        // 流程功能关闭时不再提示保存为流程（ADR-0030）。
        if (!configProvider.current().flowsEnabled()) return
        val minSteps = configProvider.current()[FlowConfig.SAVE].suggestMinSteps
        val steps = events.count { (it.payload as? ToolResultEvent)?.let { r -> r.ok && r.replay != null } == true }
        if (steps < minSteps) return
        val savedAlready =
            events.any { ev ->
                (ev.payload as? AssistantMessage)?.blocks?.any { b ->
                    b is Block.ToolCall && b.name == SAVE_FLOW_TOOL
                } ==
                    true
            }
        if (!savedAlready) notifier.notifyFlowSuggestion(record.id)
    }

    /** 成功 Run 收口后录制回放脚本存回 Task（docs/tech/04 §3.12，R1/A6）。 */
    @Suppress("ReturnCount") // 录制资格逐条早退（无任务/非成功/开关关/不适合录制）
    private suspend fun recordReplayScript(
        task: Task?,
        record: RunRecord,
        finished: RunFinished,
        events: List<ThreadEvent>,
    ) {
        if (task == null || finished.status != RunOutcome.SUCCEEDED) return
        val config = configProvider.current()[TaskConfig.REPLAY]
        if (!config.enabled) return
        val script = ReplayRecorder.build(events, config.maxSteps) ?: return
        taskStore.save(task.copy(replayScript = script, updatedAt = clock.nowMillis()))
        logger.i(TAG, "replay_script_recorded", "runId" to record.id.value, "steps" to script.steps.size)
    }

    /** 后台 Run 结束通知（docs/tech/07 §4.3/§7）。 */
    private fun notifyIfBackground(record: RunRecord) {
        if (record.origin == RunOrigin.CHAT) return
        notifier.notifyResult(record)
    }

    /** 按配置回到原 App（docs/tech/07 §4.3）。 */
    private suspend fun returnToOrigin(
        task: Task?,
        originPkg: String?,
    ) {
        if (task == null || originPkg == null) return
        if (!configProvider.current()[TaskConfig.TASK].returnToOriginApp) return
        runCatching { appLauncher.launch(originPkg) }
            .onFailure { logger.w(TAG, "return_to_origin_failed", "error" to (it.message ?: "unknown")) }
    }

    private data class RunExecution(
        val finished: RunFinished,
        val gate: RunScreenGate?,
        val originPackage: String?,
    )

    private companion object {
        const val TAG = "RunRunner"
        const val RUN_ORIGIN_KEY = "runOrigin"

        /** 任务运行禁用的工具：任务不能再创建或修改任务。 */
        val TASK_RUN_DENIED_TOOLS: Set<String> = setOf("tool.create_task", "tool.update_task")
        const val RUN_ORIGIN_FOREGROUND = "foreground"
        const val RUN_ORIGIN_BACKGROUND = "background"
        const val KEY_POLICY = "policy"
        const val TRIGGER_PREFIX = "trigger."
        const val EXTRACTED_PREFIX = "extracted."
        const val EXTRACTED_KEY = "extracted"
    }
}
