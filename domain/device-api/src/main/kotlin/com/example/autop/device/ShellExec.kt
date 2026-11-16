/**
 * shell 执行端口（shell-exec 设计 §5、ADR-0042）：在某个 Run 的专属 shell 宿主上执行一条命令。
 *
 * 同步语义：调用方等待命令结束。同一 Run 的多个并发调用共享该 Run 的宿主，各自是独立进程组。
 * 工具层据返回值映射 `ok` 与错误码；端口只把“未派发即失败”以 [Outcome.Err] 返回，
 * 其余（含超时、取消、传输失败）都以 [ShellExecResult] 返回，并带状态。
 */
package com.example.autop.device

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId

/** 命令的终止原因。 */
public enum class ShellJobState {
    /** 命令正常结束（退出码见 [ShellExecResult.exitCode]）。 */
    DONE,

    /** 超过时限，进程组已被回收。 */
    TIMED_OUT,

    /** 被调用方取消（停止、降档、暂停中的屏幕命令），进程组已被回收。 */
    CANCELLED,

    /** 宿主连接或协议失败，本次命令结果不完整。 */
    TRANSPORT_FAILED,
}

/** 一次已派发命令的结果。 */
public data class ShellExecResult(
    val state: ShellJobState,
    /** 进程退出码；被超时/取消/传输失败时可能为 null。 */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** 输出是否被截断（头尾保留，中间省略）。 */
    val truncated: Boolean,
    /** 被省略的字节数（stdout 与 stderr 合计）。 */
    val omittedBytes: Long,
    val durationMs: Long,
)

/** shell 执行端口。 */
public interface ShellExec {
    /**
     * 在 [owner] 的宿主上同步执行 [command]。
     *
     * [timeoutMs] 为命令时限（正整数，由调用方校验）。
     * 返回 [Outcome.Err] 时命令未派发：错误码见 [ShellExecErrorCodes]。
     */
    public suspend fun exec(
        owner: RunId,
        command: String,
        timeoutMs: Long,
    ): Outcome<ShellExecResult>

    /** 回收 [owner] 的宿主及其全部进程组。幂等。 */
    public suspend fun releaseRun(owner: RunId)

    /** 回收全部宿主。幂等。 */
    public suspend fun closeAll()
}

/** shell 执行端口的错误码（稳定字符串，发布后不改）。 */
public object ShellExecErrorCodes {
    /** 通道未连接或宿主无法启动。 */
    public const val UNAVAILABLE: String = "device.shell_unavailable"

    /** 命令参数非法（空、过长、含控制字符）。 */
    public const val INVALID_ARGUMENT: String = "device.invalid_argument"

    /** 宿主或 job 槽位在等待时限内仍被占用。 */
    public const val BUSY: String = "device.shell_busy"

    /** 危险操作被用户拒绝、或确认超时（工具层使用）。 */
    public const val DENIED: String = "device.shell_denied"

    /** 命令超过时限，进程组已回收（工具层使用）。 */
    public const val TIMEOUT: String = "device.shell_timeout"

    /** 命令被取消（停止、降档、暂停中的屏幕命令，工具层使用）。 */
    public const val CANCELLED: String = "device.shell_cancelled"

    /** 宿主连接或协议失败，结果不完整（工具层使用）。 */
    public const val TRANSPORT: String = "device.shell_transport"
}
