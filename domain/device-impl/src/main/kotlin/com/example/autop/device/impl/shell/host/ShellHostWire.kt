package com.example.autop.device.impl.shell.host

/**
 * host 协议常量，客户端与 host 共用（ADR-0042、shell-exec 设计 §6.3）。
 *
 * 线路格式：每行一个 JSON 对象。客户端请求带 `id`，host 回复带相同 `id`；
 * host 启动后先输出一行 `{"ready":true,...}`，客户端据此完成握手。
 *
 * 本文件只使用 JDK，host 进程（app_process）与应用进程都能加载。
 */
internal object ShellHostWire {
    const val PROTOCOL_VERSION: Int = 1

    const val OP_START: String = "start"
    const val OP_WAIT: String = "wait"
    const val OP_CANCEL: String = "cancel"
    const val OP_SHUTDOWN: String = "shutdown"

    const val KEY_READY: String = "ready"

    const val ERR_BUSY: String = "busy"
    const val ERR_NOT_FOUND: String = "not_found"
    const val ERR_BAD_REQUEST: String = "bad_request"
    const val ERR_SPAWN: String = "spawn_failed"

    const val STATE_RUNNING: String = "running"
    const val STATE_DONE: String = "done"
    const val STATE_TIMED_OUT: String = "timed_out"
    const val STATE_CANCELLED: String = "cancelled"

    /** ADB shell 用户的 UID；客户端握手时必须校验。 */
    const val EXPECTED_UID: Int = 2000

    const val DEFAULT_OUTPUT_LIMIT: Int = 65536
    const val DEFAULT_MAX_JOBS: Int = 4
    const val DEFAULT_KILL_GRACE_MS: Long = 2000L

    /** `wait` 单次阻塞上限；超过后返回 running，由客户端再次等待。 */
    const val MAX_WAIT_MS: Long = 30_000L

    /** 命令 UTF-8 字节上限，与客户端一致（shell-exec 设计 §9.3）。 */
    const val MAX_COMMAND_BYTES: Int = 2048
}
