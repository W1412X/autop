package com.example.autop.device.impl.shell

import android.content.Context
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.config.DeviceBackgroundSection
import com.example.autop.device.impl.adb.AdbClient
import com.example.autop.device.impl.adb.AdbKey
import com.example.autop.device.impl.adb.AdbV2Stream
import com.example.autop.device.impl.adb.PreferenceAdbKeyStore
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayMain
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire
import com.example.autop.device.impl.shell.host.ShellHostMain
import com.example.autop.device.impl.shell.host.ShellHostWire
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val ADB_HOST = "127.0.0.1"
private const val ADB_KEY_NAME = "autop"
private const val HANDSHAKE_ATTEMPTS = 2
private const val TAG = "ShellHostConnector"

/**
 * 建立 shell 宿主：每个宿主使用**自己的** ADB 连接（不与注入通道共用连接或锁），
 * 经 `shell,v2,raw` 流启动 [ShellHostMain]，并校验握手（UID 与协议版本）。
 *
 * 密钥与端口复用注入通道的存储（[ADB_PREFS_NAME]、[AdbConnectPorts]）。
 */
@Singleton
public class ShellHostConnector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val connectPorts: AdbConnectPorts,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) {
        private val key: AdbKey by lazy(LazyThreadSafetyMode.NONE) {
            val prefs = context.getSharedPreferences(ADB_PREFS_NAME, Context.MODE_PRIVATE)
            AdbKey(PreferenceAdbKeyStore(prefs), ADB_KEY_NAME)
        }

        /** 建立一个已握手的宿主。握手失败重试一次；仍失败抛 [IOException]。 */
        internal suspend fun connectBackground(
            section: DeviceBackgroundSection,
            handshakeTimeoutMs: Long,
            ackTimeoutMs: Long,
        ): ShellHostClient =
            connect(
                entry = BackgroundDisplayMain::class.java.name,
                arguments = BackgroundDisplayWire.PROTOCOL_VERSION.toString(),
                version = BackgroundDisplayWire.PROTOCOL_VERSION,
                handshakeTimeoutMs = handshakeTimeoutMs,
                ackTimeoutMs = ackTimeoutMs,
                maxLineBytes = section.maxPayloadBytes,
            )

        private suspend fun connect(
            entry: String,
            arguments: String,
            version: Int,
            handshakeTimeoutMs: Long,
            ackTimeoutMs: Long,
            maxLineBytes: Int,
        ): ShellHostClient {
            var last: IOException? = null
            repeat(HANDSHAKE_ATTEMPTS) { attempt ->
                try {
                    return connectOnce(entry, arguments, version, handshakeTimeoutMs, ackTimeoutMs, maxLineBytes)
                } catch (e: IOException) {
                    last = e
                    logger.w(TAG, "host_connect_failed", "attempt" to (attempt + 1), error = e)
                }
            }
            throw last ?: IOException("host connect failed")
        }

        internal suspend fun connect(limits: ShellExecLimits): ShellHostClient =
            connect(
                entry = ShellHostMain::class.java.name,
                arguments =
                    "--max-jobs ${limits.maxJobsPerRun} " +
                        "--output-limit ${limits.outputLimitBytes} " +
                        "--kill-grace-ms ${limits.killGraceMs}",
                version = ShellHostWire.PROTOCOL_VERSION,
                handshakeTimeoutMs = limits.handshakeTimeoutMs,
                ackTimeoutMs = limits.ackTimeoutMs,
                maxLineBytes = 1_048_576,
            )

        private suspend fun connectOnce(
            entry: String,
            arguments: String,
            version: Int,
            handshakeTimeoutMs: Long,
            ackTimeoutMs: Long,
            maxLineBytes: Int,
        ): ShellHostClient {
            val port =
                connectPorts.cached() ?: connectPorts.discover() ?: throw IOException("adb connect port not found")
            val adb = AdbClient(ADB_HOST, port, key)
            val host = ShellHostClient(dispatchers.io, ackTimeoutMs, logger, maxLineBytes)
            val failure =
                runCatching {
                    handshake(
                        adb,
                        host,
                        entry,
                        arguments,
                        version,
                        handshakeTimeoutMs,
                    )
                }.exceptionOrNull()
            if (failure == null) return host
            host.close()
            adb.close()
            throw failure.asConnectFailure()
        }

        private suspend fun handshake(
            adb: AdbClient,
            host: ShellHostClient,
            entry: String,
            arguments: String,
            version: Int,
            handshakeTimeoutMs: Long,
        ) {
            blockingWithTimeout(handshakeTimeoutMs, onTimeout = { adb.close() }) {
                adb.connect()
                host.attach(AdbV2Stream.open(adb, shellService(entry, arguments), host::onFrame, host::onClosed))
            }
            val ready =
                withTimeoutOrNull(handshakeTimeoutMs) { host.awaitReady() }
                    ?: throw IOException("host handshake timed out")
            verify(ready, version)
        }

        /** 取消原样传播；其它失败统一为 IOException，供重试与报告使用。 */
        private fun Throwable.asConnectFailure(): Throwable =
            if (this is CancellationException || this is IOException) {
                this
            } else {
                IOException("shell host connect failed", this)
            }

        private fun verify(
            ready: JSONObject,
            version: Int,
        ) {
            if (ready.optInt("uid", -1) != ShellHostWire.EXPECTED_UID) {
                throw IOException("host uid mismatch")
            }
            if (ready.optInt("version", -1) != version) {
                throw IOException("host protocol mismatch")
            }
        }

        /**
         * 启动命令：`CLASSPATH=<本应用 APK> app_process /system/bin <宿主入口> <参数>`。
         * APK 路径与参数都是受控值，但仍做单引号转义。
         */
        private fun shellService(
            entry: String,
            arguments: String,
        ): String {
            val apk = context.applicationInfo.sourceDir
            val command = "CLASSPATH=${quote(apk)} app_process /system/bin $entry $arguments"
            return "shell,v2,raw:$command"
        }

        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /**
         * 在独立线程执行阻塞的 ADB 调用，并在 [timeoutMs] 内等待。超时时调用 [onTimeout]
         * （关闭 socket，解除阻塞），然后抛 [IOException]。阻塞调用本身无法被协程取消，所以必须这样做。
         */
        private suspend fun blockingWithTimeout(
            timeoutMs: Long,
            onTimeout: () -> Unit,
            block: () -> Unit,
        ) {
            val done = CompletableDeferred<Result<Unit>>()
            Thread({ done.complete(runCatching(block)) }, "autop-shell-connect").apply {
                isDaemon = true
                start()
            }
            val outcome =
                withTimeoutOrNull(timeoutMs) { done.await() }
                    ?: run {
                        onTimeout()
                        throw IOException("adb connect timed out")
                    }
            outcome.getOrThrow()
        }
    }
