package com.example.autop.task.impl.notify

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunState
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunOrigin
import com.example.autop.task.RunRecord
import com.example.autop.task.RunStatus
import com.example.autop.task.UserNotifier
import org.junit.Assert.assertEquals
import org.junit.Test

/** 只记录调用的假通知器。 */
private class RecordingNotifier : UserNotifier {
    val calls = mutableListOf<String>()

    override fun notifyResult(run: RunRecord) {
        calls += "result"
    }

    override fun notifyMessage(
        title: String,
        text: String,
        runId: RunId?,
    ) {
        calls += "message"
    }

    override fun notifyQuestion(
        run: RunRecord,
        question: String,
    ) {
        calls += "ask:${run.id.value}:$question"
    }

    override fun cancelQuestion(runId: RunId) {
        calls += "cancel-ask:${runId.value}"
    }

    override fun notifyBlocked(run: RunRecord) {
        calls += "blocked:${run.id.value}"
    }
}

public class RunAttentionNotifierTest {
    @Test
    public fun `question while not holding screen notifies once`() {
        val notifier = RecordingNotifier()
        val attention = RunAttentionNotifier(notifier)
        attention.onState(active(run("r1", question = "选哪个")), holder = null)
        attention.onState(active(run("r1", question = "选哪个")), holder = null)
        assertEquals(listOf("ask:r1:选哪个"), notifier.calls)
    }

    @Test
    public fun `question while holding screen is not notified until released`() {
        val notifier = RecordingNotifier()
        val attention = RunAttentionNotifier(notifier)
        attention.onState(active(run("r1", question = "选哪个")), holder = "r1")
        assertEquals(emptyList<String>(), notifier.calls)
        attention.onState(active(run("r1", question = "选哪个")), holder = null)
        assertEquals(listOf("ask:r1:选哪个"), notifier.calls)
    }

    @Test
    public fun `answered question cancels its notification`() {
        val notifier = RecordingNotifier()
        val attention = RunAttentionNotifier(notifier)
        attention.onState(active(run("r1", question = "选哪个")), holder = null)
        attention.onState(active(run("r1")), holder = null)
        assertEquals(listOf("ask:r1:选哪个", "cancel-ask:r1"), notifier.calls)
    }

    @Test
    public fun `switching to another run cancels the previous question`() {
        val notifier = RecordingNotifier()
        val attention = RunAttentionNotifier(notifier)
        attention.onState(active(run("r1", question = "A")), holder = null)
        attention.onState(active(run("r2", question = "B")), holder = null)
        assertEquals(listOf("ask:r1:A", "cancel-ask:r1", "ask:r2:B"), notifier.calls)
    }

    @Test
    public fun `policy block notifies once while not holding screen`() {
        val notifier = RecordingNotifier()
        val attention = RunAttentionNotifier(notifier)
        attention.onState(active(run("r1", blocked = true)), holder = null)
        attention.onState(active(run("r1", blocked = true)), holder = null)
        assertEquals(listOf("blocked:r1"), notifier.calls)
    }

    @Test
    public fun `policy block while holding screen is not notified, and resume rearms the next block`() {
        val notifier = RecordingNotifier()
        val attention = RunAttentionNotifier(notifier)
        attention.onState(active(run("r1", blocked = true)), holder = "r1")
        assertEquals(emptyList<String>(), notifier.calls)
        attention.onState(active(run("r1")), holder = null)
        attention.onState(active(run("r1", blocked = true)), holder = null)
        assertEquals(listOf("blocked:r1"), notifier.calls)
    }

    @Test
    public fun `no active run produces no calls`() {
        val notifier = RecordingNotifier()
        RunAttentionNotifier(notifier).onState(null, holder = null)
        assertEquals(emptyList<String>(), notifier.calls)
    }

    private fun run(
        id: String,
        question: String? = null,
        blocked: Boolean = false,
    ): RunRecord =
        RunRecord(
            id = RunId(id),
            taskId = null,
            threadId = ThreadId("thread-1"),
            origin = RunOrigin.CHAT,
            status = if (question != null) RunStatus.WAITING_USER else RunStatus.RUNNING,
            triggerContext = emptyMap(),
            createdAt = 0L,
            startedAt = null,
            endedAt = null,
            summary = null,
            errorCode = null,
            pauseReason = if (blocked) PauseReason.POLICY_BLOCKED else null,
            waitingQuestion = question,
        )

    private fun active(run: RunRecord): ActiveRunInfo =
        ActiveRunInfo(
            run = run,
            state = RunState.WAITING_USER,
            currentStep = null,
            stepCount = 0,
            plan = emptyList(),
        )
}
