/**
 * [RunController] 暂停门与状态机一致性（暂停翻回执行态修复）：暂停后在途执行态迁移
 * 被吞掉、状态钉在 PAUSED，恢复后执行态迁移放行。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.PauseReason
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.RunRequest
import com.example.autop.agent.RunState
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle（TestScope 调度推进）
class RunControllerTest {
    @Test
    fun `暂停后执行态迁移被吞掉 保持 PAUSED`() =
        runTest {
            val controller = newController(this)

            controller.setState(RunState.SAMPLING)
            controller.pause(PauseReason.MANUAL)
            controller.setState(RunState.EXECUTING_TOOLS)
            controller.setState(RunState.COMPACTING)

            assertThat(controller.state.value).isEqualTo(RunState.PAUSED)
        }

    @Test
    fun `恢复后执行态迁移放行`() =
        runTest {
            val controller = newController(this)
            controller.setState(RunState.SAMPLING)
            controller.pause(PauseReason.MANUAL)
            controller.resume()
            advanceUntilIdle()

            controller.setState(RunState.SAMPLING)

            assertThat(controller.state.value).isEqualTo(RunState.SAMPLING)
        }

    @Test
    fun `暂停期间等待回答与结束迁移放行`() =
        runTest {
            val controller = newController(this)
            controller.pause(PauseReason.MANUAL)

            controller.setState(RunState.WAITING_USER)
            controller.complete(RunFinished(status = RunOutcome.CANCELLED, summary = null))

            assertThat(controller.state.value).isEqualTo(RunState.FINISHED)
        }

    @Test
    fun `恢复与暂停交错 最后一下点击赢`() =
        runTest {
            val controller = newController(this)
            controller.setState(RunState.SAMPLING)
            controller.pause(PauseReason.MANUAL)
            advanceUntilIdle()

            // 恢复收尾未落地又点暂停：暂停赢，恢复的 STARTING/开门作废。
            controller.resume()
            controller.pause(PauseReason.MANUAL)
            advanceUntilIdle()

            assertThat(controller.state.value).isEqualTo(RunState.PAUSED)
        }

    private suspend fun newController(scope: TestScope): RunController {
        val store = InMemoryThreadStore(FakeClock(0L))
        val thread = store.createThread(null, null)
        return RunController(
            req =
                RunRequest(
                    runId = RunId("run-1"),
                    threadId = thread,
                    input = emptyList(),
                    goal = null,
                    vars = emptyMap(),
                    config = FakeConfigProvider().layered(),
                    toolFilter = null,
                ),
            store = store,
            scope = scope,
            eventReplay = 0,
            prompts = TestPromptRepository(),
        )
    }
}
