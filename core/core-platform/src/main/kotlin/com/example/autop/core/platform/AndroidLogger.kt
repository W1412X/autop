package com.example.autop.core.platform

import android.util.Log
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DiagnosticLogSource
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Logger
import com.example.autop.core.config.UiConfig

/**
 * [Logger] 的 Android 实现（docs/tech/02 §4.4）：logcat + 环形内存缓冲 + 文件持久化。
 *
 * - debug 构建输出全部级别；release 构建默认只输出 info 及以上（`d` 被丢弃），
 *   [verboseLogging] 开启后任何构建都输出 `d`（运行期 debug 日志开关，`ui.debug.verboseLogging`）。
 * - 同一份格式化行写三处：环形缓冲（诊断日志页）、logcat、[logFiles] 滚动文件
 *   （进程崩溃后缓冲丢失，文件是唯一取证来源，questions.md N165）。
 * - `kv` 中 key 以 `text`、`content` 结尾的值在任何构建都经 [LogLineFormatter] 脱敏（N15）。
 * - [bufferCapacity] 由配置 `ui.debug.logBufferSize` 驱动；为避免 Logger ↔ ConfigProvider
 *   的构造环（ConfigProvider 依赖 Logger），容量通过本属性注入，
 *   装配方在配置变化时调用（见 questions.md N15）。
 */
public class AndroidLogger(
    private val clock: Clock,
    private val isDebug: Boolean,
    initialBufferCapacity: Int = UiConfig.DEBUG.defaultInstance().logBufferSize,
    private val logFiles: LogFiles? = null,
) : Logger,
    DiagnosticLogSource {
    private val buffer = RingLogBuffer(initialBufferCapacity)

    /** 环形缓冲容量（`ui.debug.logBufferSize`）；调小立即裁掉最旧条目。 */
    public var bufferCapacity: Int
        get() = buffer.capacity
        set(value) {
            buffer.capacity = value
        }

    /** debug 日志开关（`ui.debug.verboseLogging`）：true 时任何构建都输出 `d` 级（questions.md N165）。 */
    public var verboseLogging: Boolean = false

    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) {
        if (isDebug || verboseLogging) write(Log.DEBUG, tag, event, kv, null)
    }

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) {
        write(Log.INFO, tag, event, kv, null)
    }

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) {
        write(Log.WARN, tag, event, kv, error)
    }

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) {
        write(Log.ERROR, tag, event, kv, error)
    }

    /** 诊断日志页读取当前缓冲（最旧在前，[DiagnosticLogSource]）。 */
    override fun snapshot(): List<String> = buffer.snapshot()

    private fun write(
        priority: Int,
        tag: String,
        event: String,
        kv: Array<out Pair<String, Any?>>,
        error: Throwable?,
    ) {
        val line = LogLineFormatter.format(clock.nowMillis(), event, kv, error)
        buffer.append(line)
        Log.println(priority, tag, line)
        logFiles?.append(line)
    }
}
