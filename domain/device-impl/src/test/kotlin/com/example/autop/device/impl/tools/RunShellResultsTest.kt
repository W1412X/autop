package com.example.autop.device.impl.tools

import com.example.autop.device.ShellExecErrorCodes
import com.example.autop.device.ShellExecResult
import com.example.autop.device.ShellJobState
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RunShellResultsTest {
    private fun result(
        state: ShellJobState,
        exitCode: Int?,
        stdout: String = "",
        stderr: String = "",
    ) = ShellExecResult(
        state = state,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        truncated = false,
        omittedBytes = 0L,
        durationMs = 12L,
    )

    @Test
    fun `non zero exit code is a completed command not a failure`() {
        val view = RunShellResults.ofResult(result(ShellJobState.DONE, 1, stderr = "no such file"), screen = false)
        assertThat(view.ok).isTrue()
        assertThat(view.errorCode).isNull()
        assertThat(view.data["commandOk"]!!.jsonPrimitive.booleanOrNull).isFalse()
        assertThat(view.data["exitCode"]!!.jsonPrimitive.contentOrNull).isEqualTo("1")
        assertThat(view.output).contains("no such file")
    }

    @Test
    fun `zero exit code is command ok`() {
        val view = RunShellResults.ofResult(result(ShellJobState.DONE, 0, stdout = "ok"), screen = true)
        assertThat(view.ok).isTrue()
        assertThat(view.data["commandOk"]!!.jsonPrimitive.booleanOrNull).isTrue()
        assertThat(view.data["screen"]!!.jsonPrimitive.booleanOrNull).isTrue()
    }

    @Test
    fun `timeout is a dispatched failure that keeps partial output`() {
        val view = RunShellResults.ofResult(result(ShellJobState.TIMED_OUT, 143, stdout = "partial"), screen = false)
        assertThat(view.ok).isFalse()
        assertThat(view.errorCode).isEqualTo(ShellExecErrorCodes.TIMEOUT)
        assertThat(view.effectMayHaveHappened).isTrue()
        assertThat(view.output).isEqualTo("partial")
        assertThat(view.data["timedOut"]!!.jsonPrimitive.booleanOrNull).isTrue()
    }

    @Test
    fun `cancelled and transport failures map to their codes and stay dispatched`() {
        val cancelled = RunShellResults.ofResult(result(ShellJobState.CANCELLED, null), screen = false)
        assertThat(cancelled.errorCode).isEqualTo(ShellExecErrorCodes.CANCELLED)
        assertThat(cancelled.effectMayHaveHappened).isTrue()

        val transport = RunShellResults.ofResult(result(ShellJobState.TRANSPORT_FAILED, null), screen = false)
        assertThat(transport.errorCode).isEqualTo(ShellExecErrorCodes.TRANSPORT)
        assertThat(transport.effectMayHaveHappened).isTrue()
        assertThat(transport.data["exitCode"]!!.toString()).isEqualTo("null")
    }

    @Test
    fun `not dispatched failure never claims a side effect`() {
        val view = RunShellResults.ofNotDispatched(ShellExecErrorCodes.DENIED)
        assertThat(view.ok).isFalse()
        assertThat(view.errorCode).isEqualTo(ShellExecErrorCodes.DENIED)
        assertThat(view.effectMayHaveHappened).isFalse()
        assertThat(view.output).isEmpty()
    }

    @Test
    fun `stdout and stderr are combined with a separator only when both exist`() {
        assertThat(RunShellResults.ofResult(result(ShellJobState.DONE, 0, stdout = "a"), false).output).isEqualTo("a")
        assertThat(RunShellResults.ofResult(result(ShellJobState.DONE, 0, stderr = "e"), false).output)
            .isEqualTo("--- stderr ---\ne")
        assertThat(RunShellResults.ofResult(result(ShellJobState.DONE, 0, "a", "e"), false).output)
            .isEqualTo("a\n--- stderr ---\ne")
    }
}
