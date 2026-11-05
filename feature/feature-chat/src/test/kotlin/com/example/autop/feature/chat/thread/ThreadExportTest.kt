/**
 * 对话日志导出文本组装（docs/tech/08 §4.2，N175）：头部 + 事件 JSON lines + 应用日志。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.agent.EventPayload
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadExportTest {
    @Test
    fun `export contains header, events and log text`() {
        val events =
            listOf(
                event(1, user("send sms"), RunId("run-1")),
                event(2, user("ok"), RunId("run-1")),
            )
        val text = buildThreadExport("t1", events, "log-line\n")
        assertThat(text).contains("# thread=t1")
        assertThat(text).contains("\"seq\":1,\"createdAt\":1000,\"runId\":\"run-1\"")
        assertThat(text).contains("send sms")
        assertThat(text).contains("log-line")
        // 事件段在日志段之前，且事件按 seq 有序。
        assertThat(text.indexOf("send sms")).isLessThan(text.indexOf("log-line"))
        assertThat(text.indexOf("\"seq\":1")).isLessThan(text.indexOf("\"seq\":2"))
    }

    @Test
    fun `export without events still includes log text`() {
        val text = buildThreadExport("new", emptyList(), "log-line\n")
        assertThat(text).contains("== 应用运行日志")
        assertThat(text).contains("log-line")
    }

    @Test
    fun `export writes null run id when absent`() {
        val text = buildThreadExport("t1", listOf(event(1, user("hi"), null)), "")
        assertThat(text).contains("\"runId\":null")
    }

    private fun event(
        seq: Long,
        payload: EventPayload,
        runId: RunId?,
    ): ThreadEvent =
        ThreadEvent(
            threadId = ThreadId("t1"),
            seq = seq,
            runId = runId,
            createdAt = seq * 1000,
            payload = payload,
        )

    private fun user(text: String): EventPayload =
        UserMessage(blocks = listOf(Block.Text(text)), kind = UserMessageKind.INPUT)
}
