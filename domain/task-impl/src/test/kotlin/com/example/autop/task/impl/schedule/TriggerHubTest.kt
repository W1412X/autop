/**
 * [TriggerHub] 单测（docs/tech/07 §9）：过滤、冷却、每日上限、去重、
 * LLM 判定失败处理与每小时全局上限。
 */
package com.example.autop.task.impl.schedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.TaskId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.task.ConditionResult
import com.example.autop.task.FilterClause
import com.example.autop.task.FilterGroup
import com.example.autop.task.FilterOp
import com.example.autop.task.GroupMode
import com.example.autop.task.RunOrigin
import com.example.autop.task.Task
import com.example.autop.task.Trigger
import com.example.autop.task.TriggerEvent
import com.example.autop.task.core.FireGate
import com.example.autop.task.impl.db.TasksDb
import com.example.autop.task.impl.store.RunStore
import com.example.autop.task.impl.store.TaskStore
import com.example.autop.task.impl.store.TriggerStateStore
import com.example.autop.task.impl.testing.FakeEvaluator
import com.example.autop.task.impl.testing.FakeLogger
import com.example.autop.task.impl.testing.FakeTriggerSource
import com.example.autop.task.impl.testing.RecordingTriggerRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 触发事件的 source id（测试专用源）。 */
private const val SOURCE_ID: String = "trigger.test"

/** 冷却期（与 `task.limits.minCooldownMs` 默认下限一致）。 */
private const val COOLDOWN_MS: Long = 60_000L

/** 07 §9 的 TriggerHub 行为矩阵。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
public class TriggerHubTest {
    @Test
    public fun `filter mismatch never enqueues`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.saveTask(conditionTask("t1"))
            fixture.hub.onEvent(eventOf("k1", mapOf("pkg" to "com.other")))
            assertTrue(fixture.runner.calls.isEmpty())
        }

    @Test
    public fun `matching event enqueues with trigger vars`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.evaluator.result = Outcome.Ok(ConditionResult(match = true, extracted = mapOf("amount" to "10")))
            val task = conditionTask("t1", llmCondition = "金额超 5 元")
            fixture.saveTask(task)
            fixture.hub.onEvent(eventOf("k1", mapOf("pkg" to "com.a", "text" to "hi")))
            val call = fixture.runner.calls.single()
            assertEquals(task.id, call.task.id)
            assertEquals(RunOrigin.CONDITION, call.origin)
            assertEquals("com.a", call.vars["trigger.pkg"])
            assertEquals("hi", call.vars["trigger.text"])
            assertEquals("10", call.vars["trigger.extracted.amount"])
            assertEquals(SOURCE_ID, call.vars["trigger.source"])
        }

    @Test
    public fun `cooldown blocks until window passes`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            val task = conditionTask("t1")
            fixture.saveTask(task)
            fixture.hub.onEvent(eventOf("k1"))
            assertEquals(1, fixture.runner.calls.size)
            fixture.hub.onEvent(eventOf("k2"))
            assertEquals(1, fixture.runner.calls.size)
            assertEquals(FireGate.Verdict.Reason.COOLDOWN.name, fixture.hub.evaluate(task, eventOf("k3")).gate)
            fixture.clock.advanceBy(COOLDOWN_MS)
            fixture.hub.onEvent(eventOf("k3"))
            assertEquals(2, fixture.runner.calls.size)
        }

    @Test
    public fun `daily limit blocks after limit`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            val task = conditionTask("t1", dailyLimit = 2)
            fixture.saveTask(task)
            fixture.hub.onEvent(eventOf("k1"))
            fixture.clock.advanceBy(COOLDOWN_MS)
            fixture.hub.onEvent(eventOf("k2"))
            assertEquals(2, fixture.runner.calls.size)
            fixture.clock.advanceBy(COOLDOWN_MS)
            fixture.hub.onEvent(eventOf("k3"))
            assertEquals(2, fixture.runner.calls.size)
            assertEquals(FireGate.Verdict.Reason.DAILY_LIMIT.name, fixture.hub.evaluate(task, eventOf("k4")).gate)
        }

    @Test
    public fun `duplicate key is blocked`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            val task = conditionTask("t1")
            fixture.saveTask(task)
            fixture.hub.onEvent(eventOf("same-key"))
            assertEquals(1, fixture.runner.calls.size)
            fixture.clock.advanceBy(COOLDOWN_MS)
            assertEquals(FireGate.Verdict.Reason.DUPLICATE.name, fixture.hub.evaluate(task, eventOf("same-key")).gate)
            fixture.hub.onEvent(eventOf("same-key"))
            assertEquals(1, fixture.runner.calls.size)
        }

    @Test
    public fun `evaluator failure follows onEvaluatorError`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.evaluator.result = Outcome.Err(AppError(code = "llm.failed", message = "boom"))
            fixture.saveTask(conditionTask("t1", llmCondition = "重要消息"))
            fixture.hub.onEvent(eventOf("k1"))
            assertTrue(fixture.runner.calls.isEmpty())
            fixture.config.setOverrides(
                buildJsonObject {
                    put(
                        "task",
                        buildJsonObject {
                            put("conditions", buildJsonObject { put("onEvaluatorError", "fire") })
                        },
                    )
                },
            )
            fixture.clock.advanceBy(COOLDOWN_MS)
            fixture.hub.onEvent(eventOf("k2"))
            assertEquals(1, fixture.runner.calls.size)
        }

    @Test
    public fun `hourly global cap limits dispatches`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.config.setOverrides(
                buildJsonObject {
                    put(
                        "task",
                        buildJsonObject {
                            put("conditions", buildJsonObject { put("maxPerHour", 1) })
                        },
                    )
                },
            )
            fixture.saveTask(conditionTask("t1"))
            fixture.saveTask(conditionTask("t2"))
            fixture.hub.onEvent(eventOf("k1"))
            assertEquals(1, fixture.runner.calls.size)
        }
}

/** 测试装配：真实 TaskStore/TriggerStateStore + fake 判定器与入队记录器。 */
private class Fixture(
    scope: CoroutineScope,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = FakeClock()
    val config = FakeConfigProvider()
    val db: TasksDb =
        Room.inMemoryDatabaseBuilder(context, TasksDb::class.java).allowMainThreadQueries().build()
    val runStore = RunStore(db)
    val taskStore = TaskStore(db, runStore, config, knownSources = { setOf(SOURCE_ID) })
    val evaluator = FakeEvaluator()
    val runner = RecordingTriggerRunner()
    val hub =
        TriggerHub(
            taskStore = taskStore,
            stateStore = TriggerStateStore(db),
            sources = listOf(FakeTriggerSource(SOURCE_ID)),
            evaluators = listOf(evaluator),
            runner = runner,
            configProvider = config,
            clock = clock,
            scope = scope,
            logger = FakeLogger,
        )
}

/** 条件任务构造（`pkg EQUALS com.a` 的本地过滤）。 */
private fun conditionTask(
    id: String,
    llmCondition: String? = null,
    cooldownMs: Long = COOLDOWN_MS,
    dailyLimit: Int = 10,
): Task =
    Task(
        id = TaskId(id),
        name = "task-$id",
        goal = "goal",
        trigger =
            Trigger.Condition(
                sourceId = SOURCE_ID,
                filter =
                    FilterGroup(
                        mode = GroupMode.ALL,
                        clauses = listOf(FilterClause("pkg", FilterOp.EQUALS, "com.a")),
                    ),
                llmCondition = llmCondition,
                cooldownMs = cooldownMs,
                dailyLimit = dailyLimit,
            ),
        priority = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

/** 触发事件构造。 */
private fun eventOf(
    key: String,
    fields: Map<String, String> = mapOf("pkg" to "com.a"),
): TriggerEvent = TriggerEvent(sourceId = SOURCE_ID, key = key, occurredAt = 0L, fields = fields)

/** 保存任务并断言合法（TaskValidator 通过）。 */
private suspend fun Fixture.saveTask(task: Task) {
    val outcome = taskStore.save(task)
    check(outcome is Outcome.Ok) { "task save failed: $outcome" }
}
