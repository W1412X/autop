package com.example.autop.device.impl.shell

import com.example.autop.core.common.Logger
import com.example.autop.device.impl.adb.AdbV2Frames
import com.example.autop.device.impl.adb.AdbV2Stream
import com.example.autop.device.impl.shell.host.ShellHostWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 宿主连接或协议失败：在途命令的结果不完整，宿主必须被丢弃。 */
internal class ShellHostTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 宿主请求的 JSON 构造（字段名与 [ShellHostWire] 中的 host 实现一致）。 */
internal object ShellHostRequests {
    fun start(
        command: String,
        timeoutMs: Long,
    ): JSONObject =
        JSONObject()
            .put("op", ShellHostWire.OP_START)
            .put("command", command)
            .put("timeoutMs", timeoutMs)

    fun wait(
        jobId: String,
        waitMs: Long,
    ): JSONObject =
        JSONObject()
            .put("op", ShellHostWire.OP_WAIT)
            .put("jobId", jobId)
            .put("waitMs", waitMs)

    fun cancel(jobId: String): JSONObject =
        JSONObject()
            .put("op", ShellHostWire.OP_CANCEL)
            .put("jobId", jobId)
}

/**
 * 宿主 RPC 客户端（ADR-0042 §5.3）：把 JSON 行请求经 stdin 帧发出，从 stdout 帧解析回复并按 id 关联。
 *
 * 请求可以同时在途。连接断开、宿主退出或协议错误时，[isAlive] 变为 false，所有在途请求以
 * [ShellHostTransportException] 失败。一个实例只对应一个宿主连接。
 */
internal class ShellHostClient(
    private val io: CoroutineDispatcher,
    private val ackTimeoutMs: Long,
    private val logger: Logger,
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private val ready = CompletableDeferred<JSONObject>()
    private val lineBuffer = ByteArrayOutputStream()

    @Volatile
    private var stream: AdbV2Stream? = null

    @Volatile
    private var alive = true

    val isAlive: Boolean
        get() = alive

    /** 绑定已打开的流。读线程可能在绑定前已收到数据，回复会被正常缓存在 [pending] 中。 */
    fun attach(stream: AdbV2Stream) {
        this.stream = stream
    }

    /** 等待握手行 `{"ready":true,...}`。 */
    suspend fun awaitReady(): JSONObject = ready.await()

    /** 发送一个请求并等待按 id 关联的回复。超过 [timeoutMs] 视为传输失败。 */
    suspend fun request(
        op: JSONObject,
        timeoutMs: Long,
    ): JSONObject {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            if (!alive) throw ShellHostTransportException("shell host is not alive")
            val line = (op.put("id", id).toString() + "\n").toByteArray(Charsets.UTF_8)
            withContext(io) { write(line) }
            return withTimeout(timeoutMs) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw ShellHostTransportException("shell host rpc timed out", e)
        } finally {
            pending.remove(id)
        }
    }

    /** 关闭底层流（宿主读到 EOF 后清理全部 job 并退出）。幂等。 */
    fun close() {
        alive = false
        stream?.close()
    }

    /** 读线程回调：处理一个 shell v2 帧。 */
    fun onFrame(
        id: Int,
        payload: ByteArray,
    ) {
        when (id) {
            AdbV2Frames.ID_STDOUT -> onStdout(payload)
            AdbV2Frames.ID_STDERR -> logger.d(TAG, "shell_host_stderr", "bytes" to payload.size)
            AdbV2Frames.ID_EXIT -> failAll(ShellHostTransportException("shell host exited"))
            else -> Unit
        }
    }

    /** 读线程回调：流结束。 */
    fun onClosed(failure: Throwable?) {
        failAll(ShellHostTransportException("adb shell stream closed", failure))
    }

    private fun write(line: ByteArray) {
        val s = stream ?: throw ShellHostTransportException("shell host stream not attached")
        runCatching { s.writeStdin(line, ackTimeoutMs) }.getOrElse { throw writeFailure(it) }
    }

    /** 写入失败映射为传输异常；其它异常原样抛出。 */
    private fun writeFailure(e: Throwable): Throwable =
        when (e) {
            is IOException -> ShellHostTransportException("shell host write failed", e)
            is IllegalStateException -> ShellHostTransportException("shell host stream closed", e)
            else -> e
        }

    private fun onStdout(bytes: ByteArray) {
        for (b in bytes) {
            if (b == NEWLINE) {
                val line = lineBuffer.toString(Charsets.UTF_8.name())
                lineBuffer.reset()
                if (line.isNotBlank() && !handleLine(line)) return
            } else {
                lineBuffer.write(b.toInt())
            }
        }
    }

    /** 返回 false 表示协议错误，已失败全部请求。 */
    private fun handleLine(line: String): Boolean {
        val json =
            try {
                JSONObject(line)
            } catch (e: JSONException) {
                failAll(ShellHostTransportException("shell host sent malformed reply", e))
                return false
            }
        if (json.has(ShellHostWire.KEY_READY)) {
            ready.complete(json)
            return true
        }
        pending.remove(json.optInt("id", -1))?.complete(json)
        return true
    }

    private fun failAll(cause: Throwable) {
        alive = false
        ready.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private companion object {
        const val TAG = "ShellHost"
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
