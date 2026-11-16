package com.example.autop.device.impl.adb

import com.example.autop.device.impl.adb.AdbProtocol.A_CLSE
import com.example.autop.device.impl.adb.AdbProtocol.A_MAXDATA
import com.example.autop.device.impl.adb.AdbProtocol.A_OKAY
import com.example.autop.device.impl.adb.AdbProtocol.A_OPEN
import com.example.autop.device.impl.adb.AdbProtocol.A_WRTE
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条持久的 `shell,v2,raw` 双向流（ADR-0042 §5.5）。
 *
 * - 写：客户端数据包为 stdin 帧（id 0）；ADB 层每个 A_WRTE 必须等远端 A_OKAY 才能发下一个。
 * - 读：专用读线程解析远端的 stdout/stderr/exit 帧，并回 A_OKAY；流结束时调用 [onClosed] 一次。
 * - 独占一个 [AdbClient]：调用方为本流新建连接，不与注入通道共用。
 *
 * 线程：[writeStdin] 可由任意线程调用（内部串行化）；回调在读线程上执行，必须快速返回。
 */
internal class AdbV2Stream private constructor(
    private val client: AdbClient,
    private val localId: Int,
    private val remoteId: Int,
    private val onFrame: (id: Int, payload: ByteArray) -> Unit,
    private val onClosed: (failure: Throwable?) -> Unit,
) {
    /** 初始 1 个许可：第一次写不需要确认；之后每个 A_WRTE 消耗一个，远端 A_OKAY 归还。 */
    private val writeCredit = Semaphore(1)

    /** 保护单个 A_WRTE 的发送与 A_OKAY 回复，两者共享同一个 socket 输出流。 */
    private val writeLock = Any()

    /** 串行化整帧写入（一个请求的完整 stdin 帧）。 */
    private val frameLock = Any()
    private val decoder = AdbV2FrameDecoder()

    /** 读线程结束（EOF、远端关闭或出错）后为 true。 */
    @Volatile
    private var closed = false

    /** 调用方请求关闭；与 [closed] 分开，保证 EOF 之后仍会释放连接。 */
    private val closeRequested = AtomicBoolean(false)

    /**
     * 向远端 stdin 写入一段字节（会被包成 stdin 帧）。
     * 等待远端确认超过 [ackTimeoutMs] 抛 [IOException]。
     */
    fun writeStdin(
        payload: ByteArray,
        ackTimeoutMs: Long,
    ) {
        check(!closed) { "adb shell stream closed" }
        val frame = AdbV2Frames.encode(AdbV2Frames.ID_STDIN, payload)
        // 整帧持锁写入：两个请求的帧不能在 ADB 分片之间交错，否则远端帧边界会错乱。
        synchronized(frameLock) {
            var offset = 0
            while (offset < frame.size) {
                val size = minOf(A_MAXDATA, frame.size - offset)
                if (!writeCredit.tryAcquire(ackTimeoutMs, TimeUnit.MILLISECONDS)) {
                    throw IOException("adb write ack timeout")
                }
                val chunk = frame.copyOfRange(offset, offset + size)
                synchronized(writeLock) { client.write(AdbMessage(A_WRTE, localId, remoteId, chunk)) }
                offset += size
            }
        }
    }

    /** 关闭流并断开连接。幂等；读线程随之结束。 */
    fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        runCatching {
            synchronized(writeLock) { client.write(AdbMessage(A_CLSE, localId, remoteId, null as ByteArray?)) }
        }
        runCatching { client.close() }
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (true) {
                val message = client.read()
                when (message.command) {
                    A_WRTE -> {
                        decoder.feed(message.data ?: ByteArray(0), onFrame)
                        synchronized(writeLock) {
                            client.write(AdbMessage(A_OKAY, localId, message.arg0, null as ByteArray?))
                        }
                    }
                    A_OKAY -> writeCredit.release()
                    A_CLSE -> break
                    else -> throw IOException("unexpected adb command ${message.command}")
                }
            }
        } catch (e: IOException) {
            failure = e
        } catch (e: IllegalStateException) {
            failure = e
        } catch (e: IllegalArgumentException) {
            failure = e
        } finally {
            closed = true
            writeCredit.release()
            onClosed(failure)
        }
    }

    internal companion object {
        private const val LOCAL_ID = 1

        /**
         * 打开一条服务流（如 `shell,v2,raw:<命令>`）。阻塞直到远端接受或拒绝。
         * 调用方需自行给阻塞调用加超时（超时时关闭 [client] 即可解除阻塞）。
         */
        fun open(
            client: AdbClient,
            service: String,
            onFrame: (id: Int, payload: ByteArray) -> Unit,
            onClosed: (failure: Throwable?) -> Unit,
        ): AdbV2Stream {
            client.write(AdbMessage(A_OPEN, LOCAL_ID, 0, service))
            val reply = client.read()
            if (reply.command != A_OKAY) {
                throw IOException("adb open refused, command=${reply.command}")
            }
            val stream = AdbV2Stream(client, LOCAL_ID, reply.arg0, onFrame, onClosed)
            Thread({ stream.readLoop() }, "adb-shell-v2-reader").apply {
                isDaemon = true
                start()
            }
            return stream
        }
    }
}
