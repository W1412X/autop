/**
 * 条件触发中枢（docs/tech/07 §4.2）：订阅 tasks 变化，对被启用条件任务引用的
 * source 收集事件；过滤 → 闸门 → 可选 LLM 判定 → 全局每小时上限 → 入队。
 */
package com.example.autop.task.impl.schedule

import com.example.autop.core.common.Clock
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.core.common.TaskId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.TaskConfig
import com.example.autop.task.ConditionEvaluator
import com.example.autop.task.ConditionResult
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.example.autop.task.TriggerEvent
import com.example.autop.task.TriggerSource
import com.example.autop.task.core.FilterEvaluator
import com.example.autop.task.core.FireGate
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.store.TaskStore
import com.example.autop.task.impl.store.TriggerStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** 条件触发中枢。 */
@Suppress("LongParameterList") // 触发中枢的端口装配面（DI 注入）的自然结果
public class TriggerHub(
    private val taskStore: TaskStore,
    private val stateStore: TriggerStateStore,
    private val runStore: RunStore,
    private val sources: List<TriggerSource>,
    private val evaluators: List<ConditionEvaluator>,
    private val runner: TriggerRunner,
    private val configProvider: ConfigProvider,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private val hourWindow = ArrayDeque<Long>()
    private var started = false

    /** 同因拦截的留痕去重：同一 (任务, 原因) 在窗口内只留一条记录（连续拦截算同一次）。 */
    private val blockedRecordedAt = ConcurrentHashMap<String, Long>()

    /** 启动：按需收集事件源（幂等；2026-11-09 前生产代码从未调用，条件触发全链死）。 */
    @Synchronized
    public fun start() {
        if (started) return
        started = true
        sources.forEach { source ->
            source
                .events()
                .onEach { event -> onEvent(event) }
                .launchIn(scope)
        }
    }

    /** 闹钟到点（`trigger.schedule` 事件）。 */
    public suspend fun onScheduleFired(
        taskId: TaskId,
        atMillis: Long,
    ) {
        val task = taskStore.get(taskId)
        if (task == null) {
            logger.w(TAG, "fire_skipped_task_missing", "taskId" to taskId.value)
            return
        }
        val event =
            TriggerEvent(
                sourceId = SCHEDULE_SOURCE,
                key = "$taskId@$atMillis",
                occurredAt = atMillis,
                fields = mapOf(FIELD_SCHEDULED_AT to atMillis.toString()),
            )
        if (!task.enabled) {
            logger.i(TAG, "fire_skipped_task_disabled", "taskId" to taskId.value)
            recordBlocked(task, event, RunOrigin.SCHEDULE, CODE_TASK_DISABLED, TEXT_TASK_DISABLED)
            return
        }
        dispatch(task, event, origin = RunOrigin.SCHEDULE)
    }

    /** 事件到达（TriggerHub 收集触发源的事件流）。 */
    public suspend fun onEvent(event: TriggerEvent) {
        if (!configProvider.current()[TaskConfig.CONDITIONS].enabled) return
        val tasks =
            taskStore
                .enabledTasks()
                .filter { matchesSource(it, event.sourceId) }
                .sortedByDescending { it.priority }
        tasks.forEach { dispatch(it, event, origin = RunOrigin.CONDITION) }
    }

    private fun matchesSource(
        task: Task,
        sourceId: String,
    ): Boolean {
        val trigger = task.trigger as? Trigger.Condition ?: return false
        return trigger.sourceId == sourceId
    }

    /**
     * 单任务的过滤 → 闸门 → 判定 → 入队（试运行复用同一逻辑，只是不入队）。
     * 非条件触发（定时/手动）没有条件约束，直接放行——此前返回"不匹配"，
     * 定时任务到点全被 `filter_not_match` 静默拦掉。
     */
    @Suppress("ReturnCount") // 过滤/闸门/判定逐级早退是评估链的自然结构
    public suspend fun evaluate(
        task: Task,
        event: TriggerEvent,
    ): Evaluation {
        val trigger = task.trigger as? Trigger.Condition ?: return Evaluation(FilterResult(true), null, null)
        if (!FilterEvaluator.matches(trigger.filter, event.fields)) {
            return Evaluation(FilterResult(false), null, null)
        }
        val state = stateStore.get(task.id)
        val verdict =
            FireGate.check(
                state = state,
                key = event.key,
                nowMillis = clock.nowMillis(),
                cooldownMs = trigger.cooldownMs,
                dailyLimit = trigger.dailyLimit,
                zone = java.time.ZoneId.systemDefault(),
            )
        if (verdict is FireGate.Verdict.Blocked) {
            return Evaluation(FilterResult(true), verdict.reason.name, null)
        }
        val judged = judge(trigger, event)
        return Evaluation(FilterResult(true), null, judged)
    }

    @Suppress("ReturnCount") // 闸门逐条早退（07 §4.2 伪代码同构）
    private suspend fun dispatch(
        task: Task,
        event: TriggerEvent,
        origin: RunOrigin,
    ) {
        mutex.withLock {
            val result = evaluate(task, event)
            if (!result.filter.match) {
                logger.d(TAG, "filter_not_match", "taskId" to task.id.value, "source" to event.sourceId)
                return
            }
            if (result.gate != null) {
                logger.d(TAG, "gate_blocked", "taskId" to task.id.value, "reason" to result.gate)
                recordBlocked(task, event, origin, "gate_" + result.gate.lowercase(), gateText(result.gate))
                return
            }
            val judged = result.judged
            if (judged != null && !judged.match) {
                logger.d(TAG, "llm_not_match", "taskId" to task.id.value, "source" to event.sourceId)
                recordBlocked(task, event, origin, CODE_LLM_NOT_MATCH, TEXT_LLM_NOT_MATCH)
                return
            }
            if (!allowGlobalHour()) {
                logger.w(TAG, "global_hour_limit")
                recordBlocked(task, event, origin, CODE_HOUR_LIMIT, TEXT_HOUR_LIMIT)
                return
            }
            val state = stateStore.get(task.id)
            val zone = java.time.ZoneId.systemDefault()
            val window = configProvider.current()[TaskConfig.DEDUPE].window
            stateStore.save(task.id, FireGate.record(state, event.key, clock.nowMillis(), zone, window))
            val vars = buildVars(event, judged)
            logger.i(TAG, "trigger_fired", "taskId" to task.id.value, "origin" to origin.name)
            runner.enqueueTask(task, origin, vars)
        }
    }

    /** 变量映射：trigger.* + trigger.extracted.* + trigger.source（docs/tech/07 §4.2）。 */
    private fun buildVars(
        event: TriggerEvent,
        judged: ConditionResult?,
    ): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        event.fields.forEach { (k, v) -> vars["$VAR_PREFIX$k"] = v }
        judged?.extracted?.forEach { (k, v) -> vars["$EXTRACTED_PREFIX$k"] = v }
        vars[VAR_SOURCE] = event.sourceId
        return vars
    }

    private suspend fun judge(
        trigger: Trigger.Condition,
        event: TriggerEvent,
    ): ConditionResult? {
        val condition = trigger.llmCondition
        val evaluator = evaluators.firstOrNull()
        if (condition == null || evaluator == null) return null
        return when (val outcome = evaluator.evaluate(condition, event)) {
            is com.example.autop.core.common.Outcome.Ok -> outcome.value
            is com.example.autop.core.common.Outcome.Err -> {
                logger.w(TAG, "evaluator_failed", "error" to outcome.error.code)
                if (configProvider.current()[TaskConfig.CONDITIONS].onEvaluatorError == ON_ERROR_FIRE) {
                    ConditionResult(match = true, reason = "evaluator_error_fired")
                } else {
                    ConditionResult(match = false, reason = "evaluator_error_skipped")
                }
            }
        }
    }

    /** 全局每小时上限（docs/tech/07 §4.2）。 */
    private fun allowGlobalHour(): Boolean {
        val now = clock.nowMillis()
        val cutoff = now - HOUR_MS
        while (hourWindow.isNotEmpty() && hourWindow.peekFirst() < cutoff) hourWindow.pollFirst()
        if (hourWindow.size >= configProvider.current()[TaskConfig.CONDITIONS].maxPerHour) return false
        hourWindow.addLast(now)
        return true
    }

    /**
     * 被拦截的触发也留一条 [RunStatus.BLOCKED] 记录（此前只写 logcat，界面上"什么都没发生"，
     * 排查困难）。同一 (任务, 原因) 持续拦截时按窗口去重，避免刷屏。
     */
    private suspend fun recordBlocked(
        task: Task,
        event: TriggerEvent,
        origin: RunOrigin,
        code: String,
        text: String,
    ) {
        val key = task.id.value + ":" + code
        val now = clock.nowMillis()
        val prev = blockedRecordedAt.put(key, now)
        if (prev != null && now - prev < BLOCK_RECORD_DEDUPE_MS) return
        runStore.save(
            RunRecord(
                id = RunId(ids.newId()),
                taskId = task.id,
                threadId = ThreadId(BLOCKED_THREAD_ID),
                origin = origin,
                status = RunStatus.BLOCKED,
                triggerContext = buildVars(event, null),
                createdAt = now,
                startedAt = null,
                endedAt = now,
                summary = text,
                errorCode = code,
                pauseReason = null,
                waitingQuestion = null,
            ),
        )
    }

    private fun gateText(reason: String): String =
        when (reason) {
            "COOLDOWN" -> "触发被拦截：冷却期未到"
            "DAILY_LIMIT" -> "触发被拦截：今日次数已用完"
            "DUPLICATE" -> "触发被拦截：同一事件近期已触发过"
            else -> "触发被拦截：$reason"
        }

    /** 过滤结果。 */
    public data class FilterResult(
        val match: Boolean,
    )

    /** 一次判定的完整结果（试运行展示用）。 */
    public data class Evaluation(
        val filter: FilterResult,
        /** 闸门拦截原因（null = 通过）。 */
        val gate: String?,
        val judged: ConditionResult?,
    )

    public companion object {
        public const val SCHEDULE_SOURCE: String = "trigger.schedule"
        private const val TAG = "TriggerHub"
        private const val VAR_PREFIX = "trigger."
        private const val EXTRACTED_PREFIX = "trigger.extracted."
        private const val VAR_SOURCE = "trigger.source"
        private const val FIELD_SCHEDULED_AT = "scheduledAt"
        private const val ON_ERROR_FIRE = "fire"
        private const val HOUR_MS = 3600_000L

        /** 同因拦截记录的去重窗口（30 分钟）。 */
        private const val BLOCK_RECORD_DEDUPE_MS = 1_800_000L

        /** 被拦截记录不建线程，threadId 用哨兵值。 */
        private const val BLOCKED_THREAD_ID = "trigger-blocked"
        private const val CODE_LLM_NOT_MATCH = "llm_not_match"
        private const val CODE_HOUR_LIMIT = "global_hour_limit"
        private const val CODE_TASK_DISABLED = "task_disabled"
        private const val TEXT_LLM_NOT_MATCH = "触发被拦截：条件判定未通过"
        private const val TEXT_HOUR_LIMIT = "触发被拦截：每小时触发上限"
        private const val TEXT_TASK_DISABLED = "到点未执行：任务已停用"
    }
}

/** TriggerHub → RunCoordinator 的入队端口（避免循环依赖）。 */
public interface TriggerRunner {
    public suspend fun enqueueTask(
        task: Task,
        origin: RunOrigin,
        vars: Map<String, String>,
    )
}
