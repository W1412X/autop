/**
 * Run 执行（docs/tech/07 §4.3）：构建 RunRequest（goal/allowedTools/config 层叠/
 * runMode）、按 needsScreen 决定是否取屏幕租约、收口时校验 finish.outputs、
 * 后台通知与回到原 App。
 */
package com.example.autop.task.impl

import com.example.autop.agent.AgentSession
import com.example.autop.agent.ReplayConfig
import com.example.autop.agent.ReplayPlan
import com.example.autop.agent.ReplayRecorder
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ToolResultEvent
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ScreenLease
import com.example.autop.llm.Block
import com.example.autop.memory.ExperienceExtractor
import com.example.autop.memory.ExperienceRequest
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.TaskErrorCodes
import com.example.autop.task.TaskRepository
import com.example.autop.task.core.OutputSchemaValidator
import com.example.autop.task.impl.notify.UserNotifierImpl
import com.example.autop.task.impl.store.RunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** Run 执行器（供 DI 装配）。 */
@Suppress("LongParameterList", "TooManyFunctions") // 执行面端口装配（DI 注入）与收口链各环节的自然结果
public class RunRunner(
    private val agentSession: AgentSession,
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
    private val experience: ExperienceExtractor,
    private val configProvider: ConfigProvider,
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
        runStore.mutate(record.id) { it.copy(status = RunStatus.RUNNING, startedAt = startedAt) }
        val needsScreen = task?.needsScreen ?: true
        val originPkg = if (needsScreen) deviceState.current().foregroundPackage else null
        val request = buildRequest(record, task)
        val finished =
            try {
                if (needsScreen) {
                    screenLease.withLease(record.id.value) { runBlock(record, request) }
                } else {
                    runBlock(record, request)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "run_crashed", "runId" to record.id.value, error = e)
                RunFinished(status = RunOutcome.FAILED, summary = null, errorCode = CommonErrorCodes.UNKNOWN)
            }
        observer.finish(record.id, finished)
        observer.clear(record.id)
        val final = runStore.get(record.id)
        if (final != null) {
            val outputsValid = validateOutputs(task, finished, final)
            if (outputsValid) {
                val events = threadStore.events(record.threadId).filter { it.runId == record.id }
                recordReplayScript(task, record, finished, events)
                extractExperience(record, finished, events)
            }
            notifyIfBackground(final)
            returnToOrigin(task, originPkg)
        }
    }

    private suspend fun runBlock(
        record: RunRecord,
        request: RunRequest,
    ): RunFinished {
        // N217：新 Run 的盾抑制清除在启动前同步落地（见 [TouchShieldWatcher.beginRun]）。
        touchShieldWatcher.beginRun()
        val handle = agentSession.start(request, scope)
        observer.begin(record, handle)
        val watcher = scope.launch { observer.watch(record.id, handle) }
        val shieldWatcher = scope.launch { touchShieldWatcher.watch(handle) }
        try {
            return handle.await()
        } finally {
            watcher.cancelAndJoin()
            shieldWatcher.cancelAndJoin()
        }
    }

    private fun buildRequest(
        record: RunRecord,
        task: Task?,
    ): RunRequest {
        val entry = pending.take(record.id)
        return RunRequest(
            runId = record.id,
            threadId = record.threadId,
            input = entry?.input ?: emptyList(),
            goal = task?.goal,
            vars = (entry?.vars ?: emptyMap()) + mapOf(RUN_MODE_KEY to runMode(record.origin)),
            config = configFor(task),
            toolFilter = task?.allowedTools,
            replay = replayPlan(record, task),
        )
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

    private fun runMode(origin: RunOrigin): String =
        if (origin == RunOrigin.CHAT) RUN_MODE_FOREGROUND else RUN_MODE_BACKGROUND

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

    /** 成功 Run 收口后经验提炼（docs/design/agent-design.md §8，开关 `features.experienceExtraction`）。 */
    private suspend fun extractExperience(
        record: RunRecord,
        finished: RunFinished,
        events: List<ThreadEvent>,
    ) {
        if (finished.status != RunOutcome.SUCCEEDED) return
        val packageName =
            events
                .map { it.payload }
                .filterIsInstance<ToolResultEvent>()
                .mapNotNull { it.observation?.packageName }
                .lastOrNull() ?: return
        experience.extract(
            ExperienceRequest(runId = record.id, packageName = packageName, steps = stepsOf(events)),
        )
    }

    /** 本次 Run 的步骤摘要：每个工具结果一行（序号 + 工具 + 结果首行）。 */
    private fun stepsOf(events: List<ThreadEvent>): String =
        events
            .map { it.payload }
            .filterIsInstance<ToolResultEvent>()
            .mapIndexed { index, result ->
                val head =
                    result.blocks
                        .filterIsInstance<Block.Text>()
                        .firstOrNull()
                        ?.text
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.take(STEP_TEXT_CHARS)
                val status = if (result.ok) STEP_OK else STEP_ERROR
                "${index + 1}. ${result.toolId} [$status]${head?.let { " $it" } ?: ""}"
            }.takeLast(STEPS_MAX_LINES)
            .joinToString("\n")

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

    private companion object {
        const val TAG = "RunRunner"
        const val RUN_MODE_KEY = "runMode"
        const val RUN_MODE_FOREGROUND = "foreground"
        const val RUN_MODE_BACKGROUND = "background"
        const val KEY_POLICY = "policy"

        /** 步骤摘要：每行结果首行截断长度与最大行数。 */
        const val STEP_TEXT_CHARS = 60
        const val STEPS_MAX_LINES = 40
        const val STEP_OK = "ok"
        const val STEP_ERROR = "error"
    }
}
