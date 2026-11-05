/**
 * 04 §6 项 3：工具路由与执行链的错误路径——未知工具 / 参数 JSON 非法 / 参数校验失败 /
 * 异常转 TOOL_CRASHED / 超时转 TOOL_TIMEOUT，错误结果回传后循环继续。
 */
package com.example.autop.agent.runtime

import com.example.autop.agent.AgentErrorCodes
import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ToolResultEvent
import com.example.autop.agent.runtime.testing.FakeTool
import com.example.autop.agent.runtime.testing.RuntimeHarness
import com.example.autop.agent.runtime.testing.overridesOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolRoutingTest {
    @Test
    fun `未知工具返回 unknown_tool 并继续循环`() =
        runTest {
            val h = RuntimeHarness()
            h.llm
                .call("no_such_tool" to "{}")
                .say("好")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.ok).isFalse()
            assertThat(result.errorCode).isEqualTo(AgentErrorCodes.UNKNOWN_TOOL)
        }

    @Test
    fun `参数不是 JSON 对象返回 invalid_args_json`() =
        runTest {
            val h = RuntimeHarness(tools = setOf(FakeTool()))
            h.llm
                .call(FakeTool.FAKE_NAME to "[1,2]")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.ok).isFalse()
            assertThat(result.errorCode).isEqualTo(AgentErrorCodes.INVALID_ARGS_JSON)
        }

    @Test
    fun `缺必填参数返回 invalid_args`() =
        runTest {
            val tool = FakeTool(requiredArg = "id")
            val h = RuntimeHarness(tools = setOf(tool))
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.ok).isFalse()
            assertThat(result.errorCode).isEqualTo(AgentErrorCodes.INVALID_ARGS)
            assertThat(tool.invocations).isEmpty()
        }

    @Test
    fun `工具抛异常转 tool_crashed 且不外传`() =
        runTest {
            val tool =
                FakeTool { _, _ -> throw IllegalStateException("boom") }
            val h = RuntimeHarness(tools = setOf(tool))
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            val finished = h.start(this, threadId = thread).await()

            assertThat(finished.status).isEqualTo(RunOutcome.SUCCEEDED)
            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.ok).isFalse()
            assertThat(result.errorCode).isEqualTo(AgentErrorCodes.TOOL_CRASHED)
        }

    @Test
    fun `工具超时转 tool_timeout`() =
        runTest {
            val tool = FakeTool(delayMs = 60_000)
            val h =
                RuntimeHarness(
                    tools = setOf(tool),
                    configOverrides = overridesOf("agent", "tools", "defaultTimeoutMs" to 1_000L),
                )
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread).await()

            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.ok).isFalse()
            assertThat(result.errorCode).isEqualTo(AgentErrorCodes.TOOL_TIMEOUT)
        }

    @Test
    fun `toolFilter 过滤的工具按未知工具处理`() =
        runTest {
            val h = RuntimeHarness(tools = setOf(FakeTool()))
            h.llm
                .call(FakeTool.FAKE_NAME to "{}")
                .say("好")
            val thread = h.newThread()

            h.start(this, threadId = thread, toolFilter = emptySet()).await()

            val result =
                h.store
                    .payloadsOf(thread)
                    .filterIsInstance<ToolResultEvent>()
                    .single()
            assertThat(result.errorCode).isEqualTo(AgentErrorCodes.UNKNOWN_TOOL)
        }
}
