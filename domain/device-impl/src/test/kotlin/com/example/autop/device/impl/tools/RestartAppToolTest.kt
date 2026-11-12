/**
 * restart_app（docs/design/tool_optimize/02 §9、§13.3、06 §1.3）：
 * 强制结束成功但启动失败时错误 trace 写明已强制结束；
 * 受保护包名来自 restart_rules.json。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.PreparedAction
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RestartAppToolTest {
    @Test
    fun `launch failure after force stop reports the stopped trace`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            val launcher = FakeAppLauncher(launchOk = false)
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.restartAppTool(shell, launcher)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"package":"com.example.target"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isFalse()
            assertThat(out.error?.code).isEqualTo(DeviceErrorCodes.LAUNCH_FAILED)
            assertThat(shell.forceStopped).containsExactly("com.example.target")
            assertThat(out.trace.joinToString("\n")).contains("已强制结束 com.example.target")
            assertThat(out.trace.joinToString("\n")).contains("重新打开失败")
        }

    @Test
    fun `protected package from restart rules is denied`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            val launcher = FakeAppLauncher()
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.restartAppTool(shell, launcher)
            val ctx = env.ctx()

            val got = tool.prepare(args("""{"package":"com.android.settings"}"""), ctx)

            val error = (got as Outcome.Err).error
            assertThat(error.code).isEqualTo(DeviceErrorCodes.RESTART_DENIED)
            assertThat(shell.forceStopped).isEmpty()
            assertThat(launcher.launched).isEmpty()
        }

    @Test
    fun `force stop and relaunch succeed`() =
        runTest {
            val env = ToolTestEnv()
            val shell = FakeShell()
            val launcher = FakeAppLauncher()
            env.queue(a11yScreenOf(emptyList()))
            val tool = env.restartAppTool(shell, launcher)
            val ctx = env.ctx()
            val prepared = okOf(tool.prepare(args("""{"package":"com.example.target"}"""), ctx))

            val out = tool.perform(prepared, ctx)

            assertThat(out.ok).isTrue()
            assertThat(shell.forceStopped).containsExactly("com.example.target")
            assertThat(launcher.launched).containsExactly("com.example.target")
            val trace = out.trace.joinToString("\n")
            assertThat(trace).contains("已强制结束 com.example.target")
            assertThat(trace).doesNotContain("重新打开失败")
            assertThat(out.content.toString()).contains("已强制结束并请求重新打开 com.example.target")
        }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun okOf(outcome: Outcome<PreparedAction>): PreparedAction =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected ok, was ${outcome.error.code}")
        }
}
