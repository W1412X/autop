/**
 * RunQueue 排序测试（docs/tech/07 §2、§9：排序稳定）。
 */
package com.example.autop.task.core

import com.example.autop.core.common.RunId
import com.example.autop.task.RunOrigin
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 优先级队列行为测试。 */
public class RunQueueTest {
    private val queue = RunQueue(WEIGHTS)

    @Test
    public fun `higher origin weight comes first`() {
        queue.add(entry("schedule", RunOrigin.SCHEDULE, at = 1))
        queue.add(entry("chat", RunOrigin.CHAT, at = 2))

        assertThat(queue.snapshot().map { it.runId.value })
            .containsExactly("chat", "schedule")
            .inOrder()
    }

    @Test
    public fun `task priority adds to origin weight`() {
        queue.add(entry("condition", RunOrigin.CONDITION, priority = 90, at = 1))
        queue.add(entry("manual", RunOrigin.TASK_MANUAL, priority = 0, at = 2))

        assertThat(queue.snapshot().map { it.runId.value })
            .containsExactly("condition", "manual")
            .inOrder()
    }

    @Test
    public fun `same score keeps enqueue order`() {
        queue.add(entry("b", RunOrigin.CHAT, at = 2))
        queue.add(entry("a", RunOrigin.CHAT, at = 1))
        queue.add(entry("c", RunOrigin.CHAT, at = 3))

        assertThat(queue.snapshot().map { it.runId.value })
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    public fun `equal time keeps insertion order`() {
        queue.add(entry("first", RunOrigin.CHAT, at = 5))
        queue.add(entry("second", RunOrigin.CHAT, at = 5))

        assertThat(queue.snapshot().map { it.runId.value })
            .containsExactly("first", "second")
            .inOrder()
    }

    @Test
    public fun `missing origin weight counts as zero`() {
        queue.add(entry("resume", RunOrigin.RESUME, at = 1))
        queue.add(entry("manual", RunOrigin.TASK_MANUAL, at = 2))

        assertThat(queue.snapshot().map { it.runId.value })
            .containsExactly("manual", "resume")
            .inOrder()
    }

    @Test
    public fun `peek and remove manage the head`() {
        val first = entry("first", RunOrigin.CHAT, at = 1)
        queue.add(first)
        queue.add(entry("second", RunOrigin.SCHEDULE, at = 2))

        assertThat(queue.peek()).isEqualTo(first)
        queue.remove(first)
        assertThat(queue.peek()?.runId).isEqualTo(RunId("second"))
        assertThat(queue.size).isEqualTo(1)
    }

    private fun entry(
        id: String,
        origin: RunOrigin,
        priority: Int = 0,
        at: Long,
    ): RunQueue.Entry = RunQueue.Entry(RunId(id), origin, priority, at)

    private companion object {
        val WEIGHTS: Map<String, Int> =
            mapOf(
                "CHAT" to 100,
                "TASK_MANUAL" to 80,
                "RESUME" to 60,
                "FORK" to 60,
                "SCHEDULE" to 40,
                "CONDITION" to 20,
            )
    }
}
