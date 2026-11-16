package com.example.autop.device.impl.shell.host

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * host 的请求分发（shell-exec 设计 §6.3）。
 *
 * 请求在工作线程上处理，所以多个请求可以同时在途。回复通过 [emit] 发出，由调用方负责串行化输出。
 * 支持的操作：`start`（启动 job，返回 jobId）、`wait`（等待 job 结束或超时，返回快照）、
 * `cancel`、`shutdown`。客户端的同步执行 = `start` 后反复 `wait` 直到结束。
 */
internal class ShellHostServer(
    private val maxJobs: Int,
    private val outputLimit: Int,
    private val killGraceMs: Long,
    private val emit: (JSONObject) -> Unit,
    private val onShutdown: () -> Unit,
) {
    private val jobs = ConcurrentHashMap<String, ShellHostJob>()
    private val jobSeq = AtomicLong(1L)
    private val admission = Any()
    private val workers = Executors.newCachedThreadPool(daemonFactory("autop-host-worker"))
    private val timer = Executors.newScheduledThreadPool(1, daemonFactory("autop-host-timer"))

    /** 接收一条请求并异步处理。 */
    fun handle(request: JSONObject) {
        workers.execute { dispatch(request) }
    }

    /** 回收全部 job（TERM → 宽限 → KILL → 扫描残留）并关闭执行器。 */
    fun shutdownAll() {
        jobs.values.forEach { it.cancel() }
        val deadline = System.nanoTime() + (killGraceMs * 2 + SHUTDOWN_SLACK_MS) * NANOS_PER_MILLI
        for (job in jobs.values) {
            val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (remaining > 0) job.awaitFinished(remaining)
        }
        jobs.clear()
        workers.shutdownNow()
        timer.shutdownNow()
    }

    private fun dispatch(request: JSONObject) {
        val id = request.opt(FIELD_ID)
        val op = request.optString(FIELD_OP)
        val reply =
            runCatching { route(op, request) }.getOrElse {
                if (it is IllegalArgumentException) errorReply(ShellHostWire.ERR_BAD_REQUEST) else throw it
            }
        reply.put(FIELD_ID, id)
        emit(reply)
        // 先发出 shutdown 的回复，再通知退出，保证回复不会因进程退出而丢失。
        if (op == ShellHostWire.OP_SHUTDOWN) {
            shutdownAll()
            onShutdown()
        }
    }

    private fun route(
        op: String,
        request: JSONObject,
    ): JSONObject =
        when (op) {
            ShellHostWire.OP_START -> start(request)
            ShellHostWire.OP_WAIT -> wait(request)
            ShellHostWire.OP_CANCEL -> cancel(request)
            ShellHostWire.OP_SHUTDOWN -> JSONObject().put(FIELD_OK, true)
            else -> errorReply(ShellHostWire.ERR_BAD_REQUEST)
        }

    private fun start(request: JSONObject): JSONObject {
        val command = request.optString(FIELD_COMMAND, "")
        val timeoutMs = request.optLong(FIELD_TIMEOUT_MS, -1L)
        if (!isValidStart(command, timeoutMs)) return errorReply(ShellHostWire.ERR_BAD_REQUEST)
        val job = admit(command, timeoutMs) ?: return errorReply(ShellHostWire.ERR_BUSY)
        if (!job.start()) {
            jobs.remove(job.id)
            return errorReply(ShellHostWire.ERR_SPAWN)
        }
        return JSONObject().put(FIELD_OK, true).put(FIELD_JOB_ID, job.id)
    }

    private fun isValidStart(
        command: String,
        timeoutMs: Long,
    ): Boolean =
        command.isNotEmpty() &&
            command.toByteArray(Charsets.UTF_8).size <= ShellHostWire.MAX_COMMAND_BYTES &&
            timeoutMs > 0L

    /** 占用一个 job 槽并登记；已满员时返回 null。 */
    private fun admit(
        command: String,
        timeoutMs: Long,
    ): ShellHostJob? =
        synchronized(admission) {
            if (jobs.values.count { it.isActive } >= maxJobs) {
                null
            } else {
                ShellHostJob(
                    id = "sh_${jobSeq.getAndIncrement()}",
                    command = command,
                    timeoutMs = timeoutMs,
                    outputLimit = outputLimit,
                    timer = timer,
                    killGraceMs = killGraceMs,
                ).also { jobs[it.id] = it }
            }
        }

    private fun wait(request: JSONObject): JSONObject {
        val job = jobs[request.optString(FIELD_JOB_ID, "")] ?: return errorReply(ShellHostWire.ERR_NOT_FOUND)
        val waitMs = request.optLong(FIELD_WAIT_MS, 0L).coerceIn(0L, ShellHostWire.MAX_WAIT_MS)
        if (!job.awaitFinished(waitMs)) {
            return JSONObject()
                .put(FIELD_OK, true)
                .put(FIELD_JOB_ID, job.id)
                .put(FIELD_STATE, ShellHostWire.STATE_RUNNING)
        }
        jobs.remove(job.id)
        return finalReply(job)
    }

    private fun cancel(request: JSONObject): JSONObject {
        val job = jobs[request.optString(FIELD_JOB_ID, "")] ?: return errorReply(ShellHostWire.ERR_NOT_FOUND)
        job.cancel()
        return JSONObject().put(FIELD_OK, true).put(FIELD_JOB_ID, job.id)
    }

    private fun finalReply(job: ShellHostJob): JSONObject {
        val result = job.result()
        val out = result.stdout
        val err = result.stderr
        return JSONObject()
            .put(FIELD_OK, true)
            .put(FIELD_JOB_ID, job.id)
            .put(FIELD_STATE, result.state)
            .put(FIELD_EXIT_CODE, result.exitCode ?: JSONObject.NULL)
            .put(FIELD_STDOUT, out.text)
            .put(FIELD_STDERR, err.text)
            .put(FIELD_TRUNCATED, out.truncated || err.truncated)
            .put(FIELD_OMITTED_BYTES, out.omittedBytes + err.omittedBytes)
            .put(FIELD_DURATION_MS, result.durationMs)
    }

    private fun errorReply(code: String): JSONObject = JSONObject().put(FIELD_OK, false).put(FIELD_ERROR, code)

    private companion object {
        const val FIELD_ID = "id"
        const val FIELD_OP = "op"
        const val FIELD_OK = "ok"
        const val FIELD_ERROR = "error"
        const val FIELD_COMMAND = "command"
        const val FIELD_TIMEOUT_MS = "timeoutMs"
        const val FIELD_WAIT_MS = "waitMs"
        const val FIELD_JOB_ID = "jobId"
        const val FIELD_STATE = "state"
        const val FIELD_EXIT_CODE = "exitCode"
        const val FIELD_STDOUT = "stdout"
        const val FIELD_STDERR = "stderr"
        const val FIELD_TRUNCATED = "truncated"
        const val FIELD_OMITTED_BYTES = "omittedBytes"
        const val FIELD_DURATION_MS = "durationMs"
        const val SHUTDOWN_SLACK_MS = 500L
        const val NANOS_PER_MILLI = 1_000_000L

        fun daemonFactory(name: String): ThreadFactory =
            ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
    }
}
