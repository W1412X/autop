package com.example.autop.device.impl.shell.host

import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** job 结束后的快照，供 `wait` 回复。 */
internal data class ShellHostJobResult(
    val state: String,
    val exitCode: Int?,
    val stdout: OutputSnapshot,
    val stderr: OutputSnapshot,
    val durationMs: Long,
)

/**
 * 一条命令：独立进程组、输出缓冲、时限与取消（shell-exec 设计 §5.4、§6.2）。
 *
 * 启动方式：`setsid sh -c '<脚本>' <pid文件> <命令>`。脚本先把自身 pid 写入文件再 `exec`
 * 到 `sh -c <命令>`，因此 pid 即进程组 id（setsid 之后 pgid = sid = pid）。
 * 这样不依赖 `Process.pid()`（Android 33 之前没有）。命令本身作为单个参数传递，不经二次拼接。
 *
 * 结束路径（正常退出、超时、取消）都会回收整个进程组，并扫描残留后代。宿主自身被强杀时，
 * 不会执行这些路径；由包装脚本中的看门狗（见 [SCRIPT]）经 stdin 管道 EOF 回收进程组。
 */
internal class ShellHostJob(
    val id: String,
    private val command: String,
    private val timeoutMs: Long,
    outputLimit: Int,
    private val timer: ScheduledExecutorService,
    private val killGraceMs: Long,
) {
    /** 每个流头、尾各占 limit/4，两个流合计不超过 limit。 */
    private val stdout = HeadTailBuffer(outputLimit / STREAM_SHARE, outputLimit / STREAM_SHARE)
    private val stderr = HeadTailBuffer(outputLimit / STREAM_SHARE, outputLimit / STREAM_SHARE)
    private val finished = CountDownLatch(1)
    private val lock = Any()
    private val pumps = mutableListOf<Thread>()
    private val startedNanos = System.nanoTime()

    @Volatile
    private var state: String = ShellHostWire.STATE_RUNNING

    @Volatile
    private var exitCode: Int? = null

    @Volatile
    private var durationMs: Long = 0L

    @Volatile
    private var pgid: Long = 0L

    @Volatile
    private var timeoutFuture: ScheduledFuture<*>? = null

    /** 宿主一端的 stdin 管道写端；job 结束后关闭。 */
    @Volatile
    private var stdinPipe: OutputStream? = null

    /** 未结束（尚未发布结果）为 true；占用 job 槽。 */
    val isActive: Boolean
        get() = finished.count > 0L

    /**
     * 启动进程并等待 pid 就绪。在工作线程调用。失败返回 false，调用方丢弃本 job。
     */
    fun start(): Boolean {
        val pidFile = File.createTempFile("autop-job-", ".pid")
        val graceArg = String.format(Locale.ROOT, "%.3f", killGraceMs / MS_PER_SECOND)
        val process =
            runCatching {
                ProcessBuilder(SETSID, "sh", "-c", SCRIPT, pidFile.absolutePath, command, graceArg).start()
            }.getOrNull()
        if (process == null) {
            pidFile.delete()
            return false
        }
        // stdin 管道由宿主持有：宿主结束时管道关闭，包装脚本里的看门狗据此回收进程组。
        stdinPipe = process.outputStream
        pumps += pump(process.inputStream, stdout)
        pumps += pump(process.errorStream, stderr)
        val pid = awaitPid(pidFile)
        pidFile.delete()
        if (pid == null) {
            process.destroy()
            runCatching { process.outputStream.close() }
            return false
        }
        pgid = pid
        if (timeoutMs > 0L) {
            timeoutFuture =
                timer.schedule(
                    Runnable { terminate(ShellHostWire.STATE_TIMED_OUT) },
                    timeoutMs,
                    TimeUnit.MILLISECONDS,
                )
        }
        Thread({ awaitExit(process) }, "autop-job-waiter").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** 等待结束，最多 [waitMs] 毫秒。返回是否已结束。 */
    fun awaitFinished(waitMs: Long): Boolean = finished.await(waitMs, TimeUnit.MILLISECONDS)

    /** 取消：TERM 进程组，宽限后 KILL。已结束则无效果。 */
    fun cancel() {
        terminate(ShellHostWire.STATE_CANCELLED)
    }

    /** 结束后的结果快照；未结束时调用结果不完整，调用方应先 [awaitFinished]。 */
    fun result(): ShellHostJobResult =
        ShellHostJobResult(
            state = state,
            exitCode = exitCode,
            stdout = stdout.snapshot(),
            stderr = stderr.snapshot(),
            durationMs = durationMs,
        )

    /** 以给定终止状态结束 job：TERM 进程组，宽限后 KILL 并扫描残留。 */
    private fun terminate(newState: String) {
        synchronized(lock) {
            if (state != ShellHostWire.STATE_RUNNING) return
            state = newState
        }
        ShellHostProcess.signalGroup(pgid, OsConstants.SIGTERM)
        runCatching {
            timer.schedule(Runnable { killGroup() }, killGraceMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun killGroup() {
        ShellHostProcess.signalGroup(pgid, OsConstants.SIGKILL)
        ShellHostProcess.reapJob(pgid, ShellHostProcess.selfPid)
    }

    private fun awaitExit(process: Process) {
        val code =
            try {
                process.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
        exitCode = code
        timeoutFuture?.cancel(false)
        synchronized(lock) {
            if (state == ShellHostWire.STATE_RUNNING) state = ShellHostWire.STATE_DONE
        }
        cleanupAfterNormalExit()
        pumps.forEach { it.join(PUMP_JOIN_MS) }
        runCatching { stdinPipe?.close() }
        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        finished.countDown()
    }

    /** 主进程正常退出后，若进程组里还有后台残留，先 TERM、宽限、再 KILL。 */
    private fun cleanupAfterNormalExit() {
        if (!ShellHostProcess.hasMembers(pgid)) return
        ShellHostProcess.signalGroup(pgid, OsConstants.SIGTERM)
        CountDownLatch(1).await(killGraceMs, TimeUnit.MILLISECONDS)
        killGroup()
    }

    private fun awaitPid(pidFile: File): Long? {
        val gate = CountDownLatch(1)
        repeat(PID_POLL_ATTEMPTS) {
            val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
            if (pid != null && pid > 0L) return pid
            gate.await(PID_POLL_MS, TimeUnit.MILLISECONDS)
        }
        return null
    }

    private fun pump(
        input: InputStream,
        buffer: HeadTailBuffer,
    ): Thread =
        Thread({
            // 进程被杀后管道关闭属于正常结束，读取失败不计为错误，因此忽略结果。
            runCatching {
                input.use { stream ->
                    val chunk = ByteArray(READ_CHUNK)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        buffer.append(chunk, n)
                    }
                }
            }
        }, "autop-job-pump").apply {
            isDaemon = true
            start()
        }

    private companion object {
        /**
         * 包装脚本（宿主失联时回收 job，shell-exec 设计 §5.4）。$0 为 pid 文件，$1 为命令，$2 为回收宽限秒数。
         *
         * 宿主持有 job stdin 管道的写端。宿主不论以何种方式结束（正常退出或被 SIGKILL），内核都会关闭该写端，
         * 看门狗在 `read` 上得到 EOF，于是 TERM 整个进程组、等待宽限、再 KILL。
         * 宿主主动 TERM（取消、超时）时看门狗直接退出，不增加宿主侧的等待。
         * 命令正常结束时，包装进程先 KILL 看门狗再退出，不产生额外等待。
         */
        val SCRIPT: String =
            listOf(
                "echo \$\$ > \"\$0\"",
                "JOB=\$\$",
                "exec 3<&0 </dev/null",
                "(",
                "  trap 'exit 0' TERM",
                "  read -r _ <&3",
                "  trap '' TERM",
                "  kill -TERM -- -\"\$JOB\" 2>/dev/null",
                "  sleep \"\$2\"",
                "  kill -KILL -- -\"\$JOB\" 2>/dev/null",
                ") &",
                "W=\$!",
                "sh -c \"\$1\" 3<&-",
                "RC=\$?",
                "kill -KILL \"\$W\" 2>/dev/null",
                "exit \"\$RC\"",
            ).joinToString("\n")
        const val MS_PER_SECOND: Double = 1000.0
        const val SETSID: String = "setsid"
        const val PID_POLL_ATTEMPTS: Int = 60
        const val PID_POLL_MS: Long = 50L
        const val PUMP_JOIN_MS: Long = 2000L
        const val READ_CHUNK: Int = 8192

        /** 每个流的头部（与尾部各一份）占总预算的 1/4。 */
        const val STREAM_SHARE: Int = 4
    }
}
