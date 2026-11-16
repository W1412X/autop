package com.example.autop.device.impl.tools

import com.example.autop.device.ShellExecErrorCodes
import com.example.autop.device.ShellExecResult
import com.example.autop.device.ShellJobState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `run_shell` 的一次结果的中间表示（shell-exec 设计 §3.3）。与 [com.example.autop.agent.ToolOutput] 解耦，
 * 便于单测；由工具本体组装为真正的工具输出（信封、提示、错误对象）。
 */
internal data class RunShellView(
    val ok: Boolean,
    /** 失败时的错误码（稳定常量，见 [ShellExecErrorCodes]）。 */
    val errorCode: String?,
    /** 失败时是否可能已经产生副作用（ADR-0041）：未派发为假，派发后为真。 */
    val effectMayHaveHappened: Boolean,
    val data: JsonObject,
    /** stdout 与 stderr 的原文，由调用方包装为不可信信封。 */
    val output: String,
)

/** `run_shell` 结果映射（纯函数）。 */
internal object RunShellResults {
    /** 非零退出码不是错误：命令跑完即 `ok=true`，用 `commandOk` 表达（设计 §3.3）。 */
    fun ofResult(
        result: ShellExecResult,
        screen: Boolean,
    ): RunShellView {
        val errorCode = errorCodeOf(result.state)
        val data =
            buildJsonObject {
                put("state", result.state.name.lowercase())
                put("commandOk", result.state == ShellJobState.DONE && result.exitCode == 0)
                put("exitCode", result.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("truncated", result.truncated)
                put("omittedBytes", result.omittedBytes)
                put("timedOut", result.state == ShellJobState.TIMED_OUT)
                put("cancelled", result.state == ShellJobState.CANCELLED)
                put("screen", screen)
                put("durationMs", result.durationMs)
            }
        return RunShellView(
            ok = errorCode == null,
            errorCode = errorCode,
            // 宿主已派发：超时、取消、传输失败都可能留下副作用，同批次后续写操作应被跳过。
            effectMayHaveHappened = true,
            data = data,
            output = combine(result.stdout, result.stderr),
        )
    }

    /** 未派发的失败：参数非法、不可用、被拒绝、槽位忙等，不产生副作用。 */
    fun ofNotDispatched(code: String): RunShellView =
        RunShellView(
            ok = false,
            errorCode = code,
            effectMayHaveHappened = false,
            data = buildJsonObject { put("state", "not_started") },
            output = "",
        )

    private fun errorCodeOf(state: ShellJobState): String? =
        when (state) {
            ShellJobState.DONE -> null
            ShellJobState.TIMED_OUT -> ShellExecErrorCodes.TIMEOUT
            ShellJobState.CANCELLED -> ShellExecErrorCodes.CANCELLED
            ShellJobState.TRANSPORT_FAILED -> ShellExecErrorCodes.TRANSPORT
        }

    private fun combine(
        stdout: String,
        stderr: String,
    ): String =
        when {
            stderr.isEmpty() -> stdout
            stdout.isEmpty() -> "--- stderr ---\n$stderr"
            else -> "$stdout\n--- stderr ---\n$stderr"
        }
}
