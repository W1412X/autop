/**
 * 内置控制工具（docs/tech/04 §3.9）：ask_user 的应答/超时/超时取值优先级，
 * history_recall 的 query 模式（`seq · 类型 · 片段`）与 range 模式（原文分页、截断提示、图片附带）。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.AssistantMessage
import com.example.autop.agent.RecallHit
import com.example.autop.agent.RunNoteAdded
import com.example.autop.agent.RunRequest
import com.example.autop.agent.ThreadRecall
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.agent.runtime.testing.InMemoryThreadStore
import com.example.autop.agent.runtime.testing.TestPromptRepository
import com.example.autop.agent.runtime.testing.overridesOf
import com.example.autop.agent.runtime.tools.control.AskUserTool
import com.example.autop.agent.runtime.tools.control.HistoryRecallTool
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.buildConfigSnapshot
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.llm.Block
import com.example.autop.llm.StopReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ControlToolsTest {
    @Test
    fun `ask_user 成功回答返回文本`() =
        runTest {
            val store = InMemoryThreadStore()
            var asked: Triple<String, List<String>, Long>? = null
            val ctx =
                context(store, config()) { question, options, timeoutMs ->
                    asked = Triple(question, options, timeoutMs)
                    Outcome.Ok("同意")
                }
            val args =
                buildJsonObject {
                    put("question", "继续吗？")
                    put("options", optionsOf("是", "否"))
                    put("timeout_s", 5)
                }

            val out = AskUserTool(TestPromptRepository()).invoke(args, ctx)

            assertThat(out.ok).isTrue()
            assertThat((out.content[0] as Block.Text).text).isEqualTo("同意")
            assertThat(asked).isEqualTo(Triple("继续吗？", listOf("是", "否"), 5000L))
        }

    @Test
    fun `ask_user 超时返回 ask_timeout 与提示`() =
        runTest {
            val store = InMemoryThreadStore()
            val ctx = context(store, config()) { _, _, _ -> noUserInteraction() }
            val args = buildJsonObject { put("question", "继续吗？") }

            val out = AskUserTool(TestPromptRepository()).invoke(args, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(AgentErrorCodes.ASK_TIMEOUT)
            assertThat(out.error?.hint).isEqualTo("按默认继续或结束")
            val text = (out.content[0] as Block.Text).text
            assertThat(text).contains(AgentErrorCodes.ASK_TIMEOUT)
            assertThat(text).contains("按默认继续或结束")
        }

    @Test
    fun `ask_user 无 timeout_s 时用配置超时并兜底默认`() =
        runTest {
            val store = InMemoryThreadStore()
            var timeoutFromConfig = 0L
            var timeoutFromDefault = 0L
            val args = buildJsonObject { put("question", "选哪个？") }
            val tool = AskUserTool(TestPromptRepository())

            tool.invoke(
                args,
                context(store, config()) { _, _, ms ->
                    timeoutFromConfig = ms
                    Outcome.Ok("a")
                },
            )
            tool.invoke(
                args,
                context(store, strictConfig()) { _, _, ms ->
                    timeoutFromDefault = ms
                    Outcome.Ok("a")
                },
            )

            assertThat(timeoutFromConfig).isEqualTo(595000L)
            assertThat(timeoutFromDefault).isEqualTo(1234L)
        }

    @Test
    fun `history_recall query 模式按命中渲染类型与片段`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            seedEvents(store, thread)
            val recall =
                StubRecall(
                    listOf(
                        RecallHit(1, "x", "片段A", 1.0, setOf("recall.fts")),
                        RecallHit(2, "x", "片段B", 0.5, setOf("recall.fts")),
                        RecallHit(3, "x", "片段C", 0.4, setOf("recall.vector")),
                        RecallHit(99, "x", "片段D", 0.3, setOf("recall.fts")),
                    ),
                )
            val args =
                buildJsonObject {
                    put("query", "片段")
                    put("k", 3)
                }

            val out = HistoryRecallTool(recall).invoke(args, context(store, config(), thread))

            assertThat(recall.lastK).isEqualTo(3)
            val text = (out.content[0] as Block.Text).text
            assertThat(text)
                .isEqualTo(
                    "1 · user_message · 片段A\n" +
                        "2 · assistant_message · 片段B\n" +
                        "3 · tool_result · 片段C\n" +
                        "99 · event · 片段D",
                )
        }

    @Test
    fun `history_recall query 无命中返回提示并用默认 k`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            val recall = StubRecall(emptyList())
            val args = buildJsonObject { put("query", "不存在的关键词") }

            val out = HistoryRecallTool(recall).invoke(args, context(store, config(), thread))

            assertThat(recall.lastK).isEqualTo(8)
            assertThat((out.content[0] as Block.Text).text).isEqualTo("没有找到相关记录。")
        }

    @Test
    fun `history_recall range 模式渲染原文并附带图片`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            seedEvents(store, thread)
            val args =
                buildJsonObject {
                    put("seq_from", 1)
                    put("seq_to", 100)
                    put("include_images", true)
                }

            val out = HistoryRecallTool(StubRecall(emptyList())).invoke(args, context(store, config(), thread))

            val text = (out.content[0] as Block.Text).text
            assertThat(text).contains("#1 user_message\n第一句")
            assertThat(text).contains("#2 assistant_message\n第二句")
            assertThat(text).contains("#3 tool_result\n工具结果")
            assertThat(text).doesNotContain("运行笔记")
            assertThat(out.content.filterIsInstance<Block.Image>()).hasSize(1)
        }

    @Test
    fun `history_recall range 超出 maxChars 截断并提示续看`() =
        runTest {
            val store = InMemoryThreadStore()
            val thread = store.createThread(null, null)
            seedEvents(store, thread)
            val args = buildJsonObject { put("seq_from", 1) }
            val config = FakeConfigProvider(overridesOf("agent", "recall", "maxChars" to 20)).current()

            val out = HistoryRecallTool(StubRecall(emptyList())).invoke(args, context(store, config, thread))

            val text = (out.content[0] as Block.Text).text
            assertThat(text).contains("已截断，可用 seq_from=")
            assertThat(out.content.filterIsInstance<Block.Image>()).isEmpty()
        }

    /** 构造工具执行上下文；[ask] 为 ask_user 端口替身。 */
    private fun context(
        store: InMemoryThreadStore,
        config: ConfigSnapshot,
        threadId: ThreadId = ThreadId("t1"),
        ask: suspend (String, List<String>, Long) -> Outcome<String> = { _, _, _ -> noUserInteraction() },
    ): RuntimeToolContext {
        val req =
            RunRequest(
                runId = RunId("run-ctl"),
                threadId = threadId,
                input = emptyList(),
                goal = null,
                vars = emptyMap(),
                config = config,
                toolFilter = null,
            )
        return RuntimeToolContext(req, store, ask, progress = { })
    }

    private fun config(): ConfigSnapshot = FakeConfigProvider().current()

    /** `agent.tools.timeoutMs` 不含 `tool.ask_user` 的配置（走 defaultTimeoutMs 兜底）。 */
    private fun strictConfig(): ConfigSnapshot {
        val defaults =
            buildJsonObject {
                put(
                    "agent",
                    buildJsonObject {
                        put(
                            "tools",
                            buildJsonObject {
                                put("defaultTimeoutMs", 1234)
                                put("timeoutMs", buildJsonObject { put("tool.other", 1) })
                            },
                        )
                    },
                )
            }
        return buildConfigSnapshot(defaults, AgentConfig.ALL)
    }

    /** 四类事件各一条（含图片与应跳过的非消息事件）。 */
    private suspend fun seedEvents(
        store: InMemoryThreadStore,
        thread: ThreadId,
    ) {
        val user = UserMessage(blocks = listOf(Block.Text("第一句"), IMAGE), kind = UserMessageKind.INPUT)
        store.append(thread, null, user)
        val assistant =
            AssistantMessage(
                blocks = listOf(Block.Text("第二句")),
                usage = null,
                stopReason = StopReason.END_TURN,
                modelProfileId = "test",
            )
        store.append(thread, null, assistant)
        val toolResult =
            ToolResultEvent(
                callId = "c1",
                toolId = "tool.fake",
                ok = true,
                blocks = listOf(Block.Text("工具结果")),
                durationMs = 1,
            )
        store.append(thread, null, toolResult)
        store.append(thread, null, RunNoteAdded(text = "运行笔记"))
    }

    private fun optionsOf(vararg values: String): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    /** 记录 k 与查询的检索替身。 */
    private class StubRecall(
        private val hits: List<RecallHit>,
    ) : ThreadRecall {
        var lastK: Int? = null

        override suspend fun search(
            threadId: ThreadId,
            query: String,
            k: Int,
        ): List<RecallHit> {
            lastK = k
            return hits
        }
    }

    private companion object {
        val IMAGE: Block.Image = Block.Image(fileRef = "blob:1:s.png", mime = "image/png", width = 8, height = 8)
    }
}
