/**
 * 节点动作学习（agent-harness-overhaul WP2.11）：疑点记在本 Run 的设备历史里，
 * 不跨 Run 泄漏；证据跨 Run 累计后判定屏蔽。
 */
package com.example.autop.device.impl.nodeaction

import com.example.autop.agent.EventPayload
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.GestureController
import com.example.autop.device.GlobalAction
import com.example.autop.device.InjectionResult
import com.example.autop.device.NodeActionBlockStore
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.Rect
import com.example.autop.device.ResolvedNode
import com.example.autop.device.impl.deviceHistory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class NodeActionRouterLearningTest {
    private val provider = FakeConfigProvider(Json.parseToJsonElement("{}").jsonObject)

    @Test
    fun `UNCHANGED after node action marks suspect in run history`() =
        runTest {
            val router = router()
            val ctx = TestContext(provider)

            router.afterNodeDone(ctx, PKG, moved = false)

            assertThat(ctx.deviceHistory().nodeActionSuspects).containsKey(PKG)
        }

    @Test
    fun `CHANGED gesture after suspect adds evidence and blocks at threshold`() =
        runTest {
            val blocks = FakeBlockStore()
            val router = router(blocks)
            val ctx = TestContext(provider)

            router.afterNodeDone(ctx, PKG, moved = false)
            assertThat(router.afterGesture(ctx, PKG, moved = true)).isFalse()
            router.afterNodeDone(ctx, PKG, moved = false)
            // blockAfterEvidence 默认 2：第二次“节点无效而手势有效”即判定屏蔽。
            assertThat(router.afterGesture(ctx, PKG, moved = true)).isTrue()
            assertThat(blocks.blocked).contains(PKG)
        }

    @Test
    fun `suspects do not leak across runs`() =
        runTest {
            val router = router()
            val first = TestContext(provider)
            val second = TestContext(provider)

            router.afterNodeDone(first, PKG, moved = false)

            assertThat(first.deviceHistory().nodeActionSuspects).containsKey(PKG)
            assertThat(second.deviceHistory().nodeActionSuspects).isEmpty()
        }

    private fun router(blocks: NodeActionBlockStore = FakeBlockStore()): NodeActionRouter =
        NodeActionRouter(FakeGestures, blocks, FixedClock)

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 1_000L
    }

    private object FakeGestures : GestureController {
        override suspend fun performNodeAction(
            target: ResolvedNode,
            actionId: Int,
            args: Map<String, Any?>,
        ): Boolean = unused()

        override suspend fun performAtPath(
            windowId: Int,
            path: List<Int>,
            expectedBounds: Rect,
            actionId: Int,
        ): NodeActionOutcome = unused()

        override suspend fun tap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): InjectionResult = unused()

        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
            jitterPx: Int,
        ): InjectionResult = unused()

        override suspend fun focusedInput(): ResolvedNode? = unused()

        override suspend fun globalAction(action: GlobalAction): InjectionResult = unused()

        override suspend fun shellKeyEvent(code: Int): Boolean = unused()

        override suspend fun shellKeyCombination(codes: List<Int>): Boolean = unused()

        private fun unused(): Nothing = error("GestureController is not used in this test")
    }

    private class FakeBlockStore : NodeActionBlockStore {
        val blocked = mutableSetOf<String>()

        override suspend fun isBlocked(packageName: String): Boolean = packageName in blocked

        override suspend fun block(
            packageName: String,
            reason: String,
        ) {
            blocked += packageName
        }

        override suspend fun unblock(packageName: String) {
            blocked -= packageName
        }

        override suspend fun blocked(): List<com.example.autop.device.NodeActionBlock> = emptyList()
    }

    private class TestContext(
        private val provider: FakeConfigProvider,
    ) : ToolContext {
        override val runId: RunId = RunId("run-${System.identityHashCode(this)}")
        override val threadId: ThreadId = ThreadId("thread-1")
        override val config: ConfigSnapshot get() = provider.current()
        override val runScope: RunScopedValues = MapRunScope()
        override val threadStore: ThreadStore = UnusedThreadStore

        override suspend fun askUser(
            question: String,
            options: List<String>,
            timeoutMs: Long,
        ): Outcome<String> = Outcome.Err(AppError("test.unused", "askUser is not used"))

        override fun emitProgress(text: String) = Unit
    }

    private class MapRunScope : RunScopedValues {
        private val values = mutableMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getOrPut(
            key: String,
            create: () -> T,
        ): T = values.getOrPut(key) { create() } as T
    }

    private object UnusedThreadStore : ThreadStore {
        override suspend fun createThread(
            title: String?,
            parent: Pair<ThreadId, Long>?,
        ): ThreadId = unused()

        override suspend fun append(
            threadId: ThreadId,
            runId: RunId?,
            payload: EventPayload,
        ): ThreadEvent = unused()

        override suspend fun events(
            threadId: ThreadId,
            fromSeq: Long,
            toSeq: Long,
        ): List<ThreadEvent> = unused()

        override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = unused()

        override fun observe(threadId: ThreadId): Flow<ThreadEvent> = emptyFlow()

        override fun observeChanges(): Flow<Unit> = emptyFlow()

        override suspend fun saveBlob(
            threadId: ThreadId,
            name: String,
            bytes: ByteArray,
        ): String = unused()

        override suspend fun listThreads(
            offset: Int,
            limit: Int,
        ): List<ThreadSummary> = unused()

        override suspend fun rename(
            threadId: ThreadId,
            title: String,
        ) = unused()

        override suspend fun titleOf(threadId: ThreadId): String? = unused()

        override suspend fun delete(threadId: ThreadId) = unused()

        override suspend fun forkablePoints(threadId: ThreadId): List<Long> = unused()

        private fun unused(): Nothing = error("ThreadStore is not used in this test")
    }

    private companion object {
        const val PKG = "com.example.app"
    }
}
