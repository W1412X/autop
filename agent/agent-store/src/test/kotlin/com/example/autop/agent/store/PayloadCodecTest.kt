/**
 * PayloadCodec 与 EventUpcaster 测试（docs/tech/04 §2.1 演进规则）。
 */
package com.example.autop.agent.store

import com.example.autop.agent.Baseline
import com.example.autop.agent.BaselineReason
import com.example.autop.agent.EventPayload
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.agent.PlanUpdated
import com.example.autop.agent.RunFinished
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.UserMessage
import com.example.autop.agent.UserMessageKind
import com.example.autop.llm.Block
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** 编解码与升级测试。 */
public class PayloadCodecTest {
    @After
    public fun tearDown() {
        EventUpcaster.clear()
    }

    @Test
    public fun `encode writes serialName type and decodes back`() {
        val payloads: List<EventPayload> =
            listOf(
                UserMessage(blocks = listOf(Block.Text("你好")), kind = UserMessageKind.INPUT),
                Baseline(reason = BaselineReason.FORK, messages = emptyList()),
                PlanUpdated(items = listOf(PlanItem("步骤", PlanStatus.DONE))),
                RunFinished(status = RunOutcome.SUCCEEDED, summary = "ok"),
            )

        for (payload in payloads) {
            val encoded = PayloadCodec.encode(payload)
            val decoded = PayloadCodec.decode(encoded.type, encoded.v, encoded.json)

            assertThat(encoded.type).isEqualTo(TYPE_NAMES.getValue(payload::class.simpleName!!))
            assertThat(decoded).isEqualTo(payload)
        }
    }

    @Test
    public fun `decode passes json unchanged without upcaster`() {
        val encoded = PayloadCodec.encode(UserMessage(blocks = listOf(Block.Text("x")), kind = UserMessageKind.STEER))

        val decoded = PayloadCodec.decode(encoded.type, encoded.v, encoded.json)

        assertThat(decoded).isEqualTo(UserMessage(blocks = listOf(Block.Text("x")), kind = UserMessageKind.STEER))
    }

    @Test
    public fun `upcast applies registered transforms stepwise`() {
        EventUpcaster.register("user_message", 1) { json -> json.replace("\"v\":1", "\"v\":2") }

        val upcast = EventUpcaster.upcast("user_message", 1, """{"v":1}""")

        assertThat(upcast).isEqualTo("""{"v":2}""")
    }

    @Test
    public fun `upcast chains multiple steps`() {
        EventUpcaster.register("user_message", 1) { json -> "$json-1" }
        EventUpcaster.register("user_message", 2) { json -> "$json-2" }

        val upcast = EventUpcaster.upcast("user_message", 1, "base")

        assertThat(upcast).isEqualTo("base-1-2")
    }

    private companion object {
        val TYPE_NAMES: Map<String, String> =
            mapOf(
                "UserMessage" to "user_message",
                "Baseline" to "baseline",
                "PlanUpdated" to "plan_updated",
                "RunFinished" to "run_finished",
            )
    }
}
