package com.example.autop.device.impl.shell.host

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * shell 宿主进程入口（ADR-0042 §5.3）。由客户端通过
 * `CLASSPATH=<本应用 APK> app_process /system/bin <本类> --max-jobs N ...` 启动，身份为 ADB shell（UID 2000）。
 *
 * 约束：
 * - 只依赖 JDK、`android.system.Os` 与 `org.json`，不依赖 DI、Compose、Room；
 * - 标准输出只用于协议（每行一个 JSON）；标准错误只输出诊断，且不含命令与输出内容；
 * - 不监听任何网络端口；标准输入关闭（客户端断开或应用进程死亡）时清理全部 job 后退出。
 *
 * R8 必须保留本类的 `main(String[])`，见 app/src/main/keepRules/rules.keep。
 */
public object ShellHostMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        val options = HostOptions.parse(args)
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8.name())
        val server =
            ShellHostServer(
                maxJobs = options.maxJobs,
                outputLimit = options.outputLimit,
                killGraceMs = options.killGraceMs,
                emit = { json -> synchronized(out) { out.println(json.toString()) } },
                onShutdown = { exitProcess(0) },
            )
        emitReady(out)

        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        generateSequence { reader.readLine() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val request = runCatching { JSONObject(line) }.getOrNull()
                if (request == null) {
                    synchronized(out) { out.println(badRequest().toString()) }
                } else {
                    server.handle(request)
                }
            }
        exitAfterCleanup(server)
    }

    private fun emitReady(out: PrintStream) {
        val ready =
            JSONObject()
                .put(ShellHostWire.KEY_READY, true)
                .put("pid", currentPid())
                .put("uid", currentUid())
                .put("version", ShellHostWire.PROTOCOL_VERSION)
        synchronized(out) { out.println(ready.toString()) }
    }

    private fun badRequest(): JSONObject = JSONObject().put("ok", false).put("error", ShellHostWire.ERR_BAD_REQUEST)

    private fun exitAfterCleanup(server: ShellHostServer): Nothing {
        server.shutdownAll()
        exitProcess(0)
    }

    private fun currentPid(): Long = File("/proc/self").canonicalFile.name.toLongOrNull() ?: -1L

    /** 从 /proc/self/status 读取真实 UID（第一个字段）。读不到时返回 -1，客户端会因握手校验失败而拒绝。 */
    private fun currentUid(): Int =
        File("/proc/self/status")
            .readLines()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1
}

/** 启动参数。未知参数忽略，缺失或非法时使用默认值。 */
internal data class HostOptions(
    val maxJobs: Int,
    val outputLimit: Int,
    val killGraceMs: Long,
) {
    companion object {
        private const val FLAG_MAX_JOBS = "--max-jobs"
        private const val FLAG_OUTPUT_LIMIT = "--output-limit"
        private const val FLAG_KILL_GRACE_MS = "--kill-grace-ms"

        fun parse(args: Array<String>): HostOptions {
            var maxJobs = ShellHostWire.DEFAULT_MAX_JOBS
            var outputLimit = ShellHostWire.DEFAULT_OUTPUT_LIMIT
            var grace = ShellHostWire.DEFAULT_KILL_GRACE_MS
            var i = 0
            while (i + 1 < args.size) {
                when (args[i]) {
                    FLAG_MAX_JOBS -> args[i + 1].toIntOrNull()?.let { maxJobs = it.coerceIn(1, MAX_JOBS_CEILING) }
                    FLAG_OUTPUT_LIMIT ->
                        args[i + 1].toIntOrNull()?.let {
                            outputLimit =
                                it.coerceIn(MIN_OUTPUT, MAX_OUTPUT)
                        }
                    FLAG_KILL_GRACE_MS -> args[i + 1].toLongOrNull()?.let { grace = it.coerceIn(0L, MAX_GRACE_MS) }
                }
                i += 2
            }
            return HostOptions(maxJobs, outputLimit, grace)
        }

        private const val MAX_JOBS_CEILING = 8
        private const val MIN_OUTPUT = 4096
        private const val MAX_OUTPUT = 1_048_576
        private const val MAX_GRACE_MS = 10_000L
    }
}
