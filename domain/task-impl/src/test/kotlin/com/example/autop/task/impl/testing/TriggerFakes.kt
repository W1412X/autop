/**
 * TriggerHub 单测的 fake 端口（docs/tech/07 §9）：触发源、条件判定器与入队记录器。
 */
package com.example.autop.task.impl.testing

import com.example.autop.core.common.Outcome
import com.example.autop.task.ConditionEvaluator
import com.example.autop.task.ConditionResult
import com.example.autop.task.RunOrigin
import com.example.autop.task.Task
import com.example.autop.task.TriggerEvent
import com.example.autop.task.TriggerSource
import com.example.autop.task.impl.schedule.TriggerRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** fake 触发源：事件流可编程发射（TriggerHub 收集用）。 */
public class FakeTriggerSource(
    override val id: String,
) : TriggerSource {
    override val fieldNames: List<String> = listOf("pkg", "text")

    override val requiresPermission: String? = null

    private val events = MutableSharedFlow<TriggerEvent>()

    override fun events(): Flow<TriggerEvent> = events

    /** 发射一个事件。 */
    public suspend fun emit(event: TriggerEvent) {
        events.emit(event)
    }
}

/** fake 条件判定器：结果可编程（默认放行）。 */
public class FakeEvaluator : ConditionEvaluator {
    override val id: String = "evaluator.fake"

    /** 下一次 [evaluate] 返回的结果。 */
    public var result: Outcome<ConditionResult> = Outcome.Ok(ConditionResult(match = true))

    override suspend fun evaluate(
        condition: String,
        event: TriggerEvent,
    ): Outcome<ConditionResult> = result
}

/** 入队记录器：记录 TriggerHub 的每次 [enqueueTask] 调用。 */
public class RecordingTriggerRunner : TriggerRunner {
    /** 一次入队调用。 */
    public data class Call(
        val task: Task,
        val origin: RunOrigin,
        val vars: Map<String, String>,
    )

    /** 已记录的调用（按顺序）。 */
    public val calls: MutableList<Call> = mutableListOf()

    override suspend fun enqueueTask(
        task: Task,
        origin: RunOrigin,
        vars: Map<String, String>,
    ) {
        calls += Call(task, origin, vars)
    }
}
