/**
 * shell 注入通道实现（N199）：无线调试 ADB 连接管理 + `input` 命令注入。
 *
 * 依赖搬运自 Shizuku 的 ADB 协议栈（`impl/adb/`）：mDNS 发现 `_adb-tls-connect._tcp`
 * 端口 → AdbClient 鉴权连接 → `shellCommand` 执行 `input tap/swipe/keyevent`。
 * 连接失效即清缓存回落 Disconnected，调用方自动降级无障碍注入。
 */
package com.example.autop.device.impl.shell

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.example.autop.core.common.AppError
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.impl.adb.AdbClient
import com.example.autop.device.impl.adb.AdbKey
import com.example.autop.device.impl.adb.AdbPairingClient
import com.example.autop.device.impl.adb.PreferenceAdbKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdbShellInput"
private const val PREFS_NAME = ADB_PREFS_NAME
private const val KEY_PAIRED = "paired"
private const val ADB_HOST = "127.0.0.1"

/** 连接探测使用的 shell 命令，无副作用。 */
private const val PROBE_COMMAND = "true"

/** shell 注入通道：连接 adbd 后以 shell 身份注入输入事件。 */
@Singleton
public class AdbShellInput
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
        private val configProvider: ConfigProvider,
        private val connectPorts: AdbConnectPorts,
    ) : ShellInput {
        private val prefs: SharedPreferences
            get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private val key: AdbKey by lazy(LazyThreadSafetyMode.NONE) {
            AdbKey(PreferenceAdbKeyStore(prefs), "autop")
        }

        private val mutex = Mutex()

        @Volatile
        private var client: AdbClient? = null

        private val mutableState = MutableStateFlow<ShellState>(ShellState.Disconnected)

        override val state = mutableState.asStateFlow()

        private val mutablePaired = MutableStateFlow(prefs.getBoolean(KEY_PAIRED, false))

        override val paired = mutablePaired.asStateFlow()

        override suspend fun pair(
            pairingPort: Int,
            pairCode: String,
        ): Outcome<Unit> =
            withContext(dispatchers.io) {
                mutex.withLock {
                    val result =
                        runCatchingNative(code = "device.adb_pair_failed") {
                            AdbPairingClient(ADB_HOST, pairingPort, pairCode, key).use { pairing ->
                                pairing.start()
                            }
                        }
                    when (result) {
                        is Outcome.Ok -> {
                            if (result.value) {
                                Log.i(TAG, "pairing succeeded on port $pairingPort")
                                prefs.edit { putBoolean(KEY_PAIRED, true) }
                                mutablePaired.value = true
                                Outcome.Ok(Unit)
                            } else {
                                Outcome.Err(AppError("device.adb_pair_failed", "配对失败（配对码错误或超时）"))
                            }
                        }
                        is Outcome.Err -> result
                    }
                }
            }

        override suspend fun probe(): Boolean =
            withContext(dispatchers.io) {
                mutex.withLock {
                    val active = client ?: return@withLock false
                    val timeoutMs = configProvider.current()[DeviceConfig.SHELL].commandTimeoutMs
                    val worker = async { runCatching { active.shellCommand(PROBE_COMMAND, null) }.isSuccess }
                    val alive = withTimeoutOrNull(timeoutMs) { worker.await() } ?: false
                    if (!alive) {
                        Log.w(TAG, "shell probe failed, dropping connection")
                        worker.cancel()
                        closeClientLocked()
                    }
                    alive
                }
            }

        override suspend fun connect(): Boolean =
            withContext(dispatchers.io) {
                mutex.withLock { ensureClientLocked() != null }
            }

        override suspend fun tap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): Boolean {
            val command =
                if (durationMs > 0) {
                    "input swipe $x $y $x $y $durationMs"
                } else {
                    "input tap $x $y"
                }
            return runShell(command)
        }

        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
        ): Boolean = runShell("input swipe $fromX $fromY $toX $toY $durationMs")

        override suspend fun keyEvent(code: Int): Boolean = runShell("input keyevent $code")

        /** 包名只允许字母、数字、点、下划线，防止 shell 注入。 */
        override suspend fun forceStop(packageName: String): Boolean {
            if (!PACKAGE_NAME.matches(packageName)) return false
            return runShell("am force-stop $packageName")
        }

        private companion object {
            val PACKAGE_NAME = Regex("[A-Za-z0-9._]+")
        }

        override suspend fun keyCombination(vararg codes: Int): Boolean =
            runShell("input keycombination ${codes.joinToString(" ")}")

        private suspend fun runShell(command: String): Boolean =
            withContext(dispatchers.io) {
                mutex.withLock {
                    val active = ensureClientLocked() ?: return@withLock false
                    runCatching {
                        active.shellCommand(command, null)
                        true
                    }.getOrElse { error ->
                        Log.w(TAG, "shell command failed: $command", error)
                        closeClientLocked()
                        false
                    }
                }
            }

        /** 加锁调用：返回可用连接（缓存 → 已知端口 → mDNS 发现），失败清缓存回落 Disconnected。 */
        private suspend fun ensureClientLocked(): AdbClient? {
            client?.let { return it }
            mutableState.value = ShellState.Connecting
            val port = discoverConnectPort()
            val opened = port?.let { openClient(it) }
            if (opened != null && port != null) {
                connectPorts.remember(port)
            }
            client = opened
            mutableState.value = if (opened != null) ShellState.Connected else ShellState.Disconnected
            return opened
        }

        /** 向端口发起 TLS 连接，失败清端口缓存。 */
        private fun openClient(port: Int): AdbClient? =
            runCatching {
                AdbClient(ADB_HOST, port, key).also { it.connect() }
            }.getOrElse { error ->
                Log.w(TAG, "connect to adbd failed on port $port", error)
                connectPorts.forget()
                null
            }

        /** 连接端口：先试上次成功的端口（无线调试重启会变，失败后落到发现），再 mDNS 发现。 */
        private suspend fun discoverConnectPort(): Int? = connectPorts.cached() ?: connectPorts.discover()

        private fun closeClientLocked() {
            runCatching { (client as? Closeable)?.close() }
            client = null
            mutableState.value = ShellState.Disconnected
        }
    }

/**
 * N201：与 runCatchingCancellable 同语义，但把 Error（如 so 缺失时的
 * [UnsatisfiedLinkError]）也转为 [Outcome.Err]——配对是唯一 JNI 路径，
 * native 库不可用必须降级为错误提示，不能把进程打崩。
 */
@Suppress("TooGenericExceptionCaught") // 防御意图就是兜住 Throwable（含 Error）：见函数注释。
private inline fun <T> runCatchingNative(
    code: String,
    block: () -> T,
): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Outcome.Err(AppError(code = code, message = t.message ?: t.toString(), cause = t))
    }
