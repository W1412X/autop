package com.example.autop.device.impl.tools

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.DisplayTarget
import com.example.autop.device.ShellExec
import com.example.autop.device.ShellExecResult
import com.example.autop.device.ShellJobState
import com.example.autop.device.impl.shell.ShellDangerRulesLoader
import com.example.autop.device.impl.shell.ShellExecLimits
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RunShellToolTest {
    @Test
    fun `background screen command is rejected before preflight and execution`() =
        runTest {
            val exec = RecordingExec()
            val tool = tool(exec)
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val output =
                withContext(display) {
                    tool.preflight(args("pm clear com.example.app", screen = true), ToolTestEnv().ctx())
                }

            assertThat(output?.error?.code).isEqualTo(DeviceErrorCodes.BG_ACTION_UNSUPPORTED)
            assertThat(exec.calls).isEmpty()
        }

    @Test
    fun `background screen command never reaches physical shell executor`() =
        runTest {
            val exec = RecordingExec()
            val tool = tool(exec)
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val output =
                withContext(display) {
                    tool.invoke(args("input tap 100 200", screen = true), ToolTestEnv().ctx())
                }

            assertThat(output.error?.code).isEqualTo(DeviceErrorCodes.BG_ACTION_UNSUPPORTED)
            assertThat(exec.calls).isEmpty()
        }

    @Test
    fun `main screen command still executes`() =
        runTest {
            val exec = RecordingExec()
            val output = tool(exec).invoke(args("echo ok", screen = true), ToolTestEnv().ctx())

            assertThat(output.ok).isTrue()
            assertThat(exec.calls).containsExactly("echo ok")
        }

    @Test
    fun `background commands cannot use screen false to reach physical shell`() =
        runTest {
            val exec = RecordingExec()
            val tool = tool(exec)
            val display = DisplayTarget.Background(RunId("background-run"), 9, 720, 1600, 320)

            val output =
                withContext(display) {
                    tool.invoke(args("input tap 100 200", screen = false), ToolTestEnv().ctx())
                }

            assertThat(output.error?.code).isEqualTo(DeviceErrorCodes.BG_ACTION_UNSUPPORTED)
            assertThat(exec.calls).isEmpty()
        }

    private fun tool(exec: ShellExec): RunShellTool {
        val context = RuntimeEnvironment.getApplication()
        return RunShellTool(
            shellExec = exec,
            shellInput = FakeShell(),
            limits = ShellExecLimits(),
            dangerRules = ShellDangerRulesLoader(context),
            prompts = FakePromptRepository(),
            context = context,
        )
    }

    private fun args(
        command: String,
        screen: Boolean,
    ): JsonObject = Json.parseToJsonElement("""{"command":"$command","screen":$screen}""") as JsonObject

    private class RecordingExec : ShellExec {
        val calls = mutableListOf<String>()

        override suspend fun exec(
            owner: RunId,
            command: String,
            timeoutMs: Long,
        ): Outcome<ShellExecResult> {
            calls += command
            return Outcome.Ok(
                ShellExecResult(
                    state = ShellJobState.DONE,
                    exitCode = 0,
                    stdout = "ok",
                    stderr = "",
                    truncated = false,
                    omittedBytes = 0,
                    durationMs = 1,
                ),
            )
        }

        override suspend fun releaseRun(owner: RunId) = Unit

        override suspend fun closeAll() = Unit
    }
}
