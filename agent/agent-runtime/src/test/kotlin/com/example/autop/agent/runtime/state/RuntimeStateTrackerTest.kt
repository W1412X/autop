/**
 * 运行状态上报（agent-harness-overhaul WP3.10）：首次全量、易变不单独上报、
 * 非易变变化出增量并随带易变刷新、条目消失按 removed 上报、新基线后自动全量、
 * 来源失败跳过并留日志。
 */
package com.example.autop.agent.runtime.state

import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.RuntimeStateReported
import com.example.autop.agent.RuntimeStateSource
import com.example.autop.agent.StateEntry
import com.example.autop.agent.StateRequest
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class RuntimeStateTrackerTest {
    @Test
    fun `first report is full`() =
        runTest {
            val env = fixture(source("s1", listOf(entry("k", "v1"))))
            val wrote = env.tracker.reportIfChanged(env.req)

            assertThat(wrote).isTrue()
            val report = env.reports().single()
            assertThat(report.full).isTrue()
            assertThat(report.entries).containsExactly("s1/k", "v1")
        }

    @Test
    fun `volatile-only change does not report`() =
        runTest {
            val env =
                fixture(
                    source(
                        "s1",
                        listOf(entry("time", "t1", volatile = true), entry("net", "n1")),
                        listOf(entry("time", "t2", volatile = true), entry("net", "n1")),
                    ),
                )
            env.tracker.reportIfChanged(env.req)

            assertThat(env.tracker.reportIfChanged(env.req)).isFalse()
            assertThat(env.reports()).hasSize(1)
        }

    @Test
    fun `non volatile change reports delta with volatile refresh`() =
        runTest {
            val env =
                fixture(
                    source(
                        "s1",
                        listOf(entry("time", "t1", volatile = true), entry("net", "n1")),
                        listOf(entry("time", "t2", volatile = true), entry("net", "n2")),
                    ),
                )
            env.tracker.reportIfChanged(env.req)

            assertThat(env.tracker.reportIfChanged(env.req)).isTrue()
            val report = env.reports().last()
            assertThat(report.full).isFalse()
            assertThat(report.text).contains("n2")
            assertThat(report.text).contains("t2")
        }

    @Test
    fun `removed entry is reported`() =
        runTest {
            val env =
                fixture(
                    source(
                        "s1",
                        listOf(entry("a", "1"), entry("b", "2")),
                        listOf(entry("a", "1")),
                    ),
                )
            env.tracker.reportIfChanged(env.req)

            assertThat(env.tracker.reportIfChanged(env.req)).isTrue()
            val report = env.reports().last()
            assertThat(report.full).isFalse()
            assertThat(report.entries).containsExactly("s1/a", "1")
        }

    @Test
    fun `report after new baseline is full`() =
        runTest {
            val env = fixture(source("s1", listOf(entry("k", "v1"))))
            env.tracker.reportIfChanged(env.req)
            env.store.append(
                env.thread,
                null,
                Baseline(reason = BaselineReason.RESUME, messages = emptyList(), notes = emptyList()),
            )

            assertThat(env.tracker.reportIfChanged(env.req)).isTrue()
            val report = env.reports().last()
            assertThat(report.full).isTrue()
        }

    @Test
    fun `failing source is skipped and logged`() =
        runTest {
            val env = fixture(FailingSource("bad"), source("s1", listOf(entry("k", "v1"))), order = listOf("bad", "s1"))

            assertThat(env.tracker.reportIfChanged(env.req)).isTrue()
            val report = env.reports().single()
            assertThat(report.entries).containsExactly("s1/k", "v1")
            assertThat(env.logger.warnings.map { it.first }).contains("state_source_failed")
        }

    private fun entry(
        key: String,
        text: String,
        volatile: Boolean = false,
    ): StateEntry = StateEntry(key, text, volatile)

    private fun source(
        id: String,
        vararg scripted: List<StateEntry>,
    ): FakeSource = FakeSource(id, scripted.toList())

    private suspend fun fixture(
        vararg sources: RuntimeStateSource,
        order: List<String> = sources.map { it.id },
    ): Fixture {
        val store = InMemoryThreadStore(FakeClock(1_000L))
        val thread = store.createThread(null, null)
        val logger = RecordingLogger()
        val config =
            FakeConfigProvider(
                Json
                    .parseToJsonElement("""{"agent":{"state":{"sources":${jsonArrayText(order)}}}}""")
                    .jsonObject,
            )
        val tracker = RuntimeStateTracker(sources.associateBy { it.id }, store, TestPromptRepository(), logger)
        val req = StateRequest(RunId("run-1"), thread, config.current(), emptyMap())
        return Fixture(tracker, store, logger, thread, req)
    }

    private fun jsonArrayText(values: List<String>): String = values.joinToString(",", "[", "]") { "\"$it\"" }

    private class FakeSource(
        override val id: String,
        private val scripted: List<List<StateEntry>>,
    ) : RuntimeStateSource {
        private var calls = 0

        override suspend fun entries(req: StateRequest): List<StateEntry> = scripted[minOf(calls++, scripted.size - 1)]
    }

    private class FailingSource(
        override val id: String,
    ) : RuntimeStateSource {
        override suspend fun entries(req: StateRequest): List<StateEntry> = error("boom")
    }

    private class RecordingLogger : Logger {
        val warnings: MutableList<Pair<String, Throwable?>> = mutableListOf()

        override fun d(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
        ) = Unit

        override fun i(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
        ) = Unit

        override fun w(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
            error: Throwable?,
        ) {
            warnings += event to error
        }

        override fun e(
            tag: String,
            event: String,
            vararg kv: Pair<String, Any?>,
            error: Throwable?,
        ) = Unit
    }

    private class Fixture(
        val tracker: RuntimeStateTracker,
        val store: InMemoryThreadStore,
        val logger: RecordingLogger,
        val thread: com.example.autop.core.common.ThreadId,
        val req: StateRequest,
    ) {
        fun reports(): List<RuntimeStateReported> = store.payloadsOf(thread).filterIsInstance<RuntimeStateReported>()
    }
}
