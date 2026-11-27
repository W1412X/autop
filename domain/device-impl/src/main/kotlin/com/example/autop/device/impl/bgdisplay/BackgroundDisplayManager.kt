package com.example.autop.device.impl.bgdisplay

import android.os.Build
import com.example.autop.core.common.AppError
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceBackgroundSection
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.DisplayTarget
import com.example.autop.device.RunDisplayController
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire
import com.example.autop.device.impl.shell.ShellHostClient
import com.example.autop.device.impl.shell.ShellHostConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 一个 Run 独占的显示会话；所有 Run 复用同一 Host，显示操作在会话内串行。 */
internal class BackgroundDisplaySession(
    val target: DisplayTarget.Background,
    val client: ShellHostClient,
    private val config: DeviceBackgroundSection,
) {
    private val sessionId = target.owner.value
    val mutex = Mutex()

    @Volatile
    var released = false
        private set

    private var terminalError: AppError? = null

    suspend fun request(op: JSONObject): Outcome<JSONObject> =
        mutex.withLock {
            terminalError?.let { return@withLock Outcome.Err(it) }
            if (released || !client.isAlive) return@withLock unavailable()
            try {
                val reply =
                    client.request(
                        JSONObject(op.toString()).apply { put("sessionId", sessionId) },
                        if (op.optString("op") == BackgroundDisplayWire.OP_LAUNCH) {
                            maxOf(config.requestTimeoutMs, config.launchTimeoutMs + RPC_GRACE_MS)
                        } else {
                            config.requestTimeoutMs
                        },
                    )
                if (reply.optBoolean("ok")) {
                    Outcome.Ok(reply)
                } else {
                    val error = hostError(reply.optString("error"))
                    if (error.abortRun) terminalError = error
                    Outcome.Err(error)
                }
            } catch (e: CancellationException) {
                terminalError = AppError(DeviceErrorCodes.BG_RELEASED, "background request cancelled", abortRun = true)
                throw e
            } catch (e: IOException) {
                // An RPC timeout cannot establish whether the helper already performed the operation.
                val error =
                    AppError(DeviceErrorCodes.BG_RELEASED, "background host disconnected", cause = e, abortRun = true)
                terminalError = error
                Outcome.Err(error)
            }
        }

    suspend fun release() {
        if (released) return
        released = true
        try {
            if (client.isAlive) {
                val reply =
                    client.request(
                        JSONObject()
                            .put("op", BackgroundDisplayWire.OP_RELEASE)
                            .put("sessionId", sessionId),
                        config.launchTimeoutMs,
                    )
                if (!reply.optBoolean("ok")) return
            }
        } catch (_: IOException) {
            // The display may still exist. Closing the owner transport forces resource retirement.
            client.close()
        }
    }

    private fun unavailable(): Outcome<JSONObject> =
        Outcome.Err(AppError(DeviceErrorCodes.BG_RELEASED, "background display session released", abortRun = true))

    private fun hostError(code: String): AppError =
        AppError(
            code = deviceErrorCode(code),
            message = code,
            retryable =
                code == BackgroundDisplayWire.ERR_STALE_NODE ||
                    code == BackgroundDisplayWire.ERR_TIMEOUT ||
                    code == BackgroundDisplayWire.ERR_FRAME_NOT_READY,
            abortRun = code in TERMINAL_HOST_ERRORS,
        )

    private fun deviceErrorCode(code: String): String = inputErrorCode(code) ?: displayErrorCode(code)

    private fun inputErrorCode(code: String): String? =
        when (code) {
            BackgroundDisplayWire.ERR_INJECTION_FAILED,
            BackgroundDisplayWire.ERR_INPUT_NOT_FOUND,
            BackgroundDisplayWire.ERR_SET_TEXT_FAILED,
            -> DeviceErrorCodes.BG_INPUT_REJECTED
            BackgroundDisplayWire.ERR_STALE_NODE -> DeviceErrorCodes.NODE_STALE
            BackgroundDisplayWire.ERR_ACTION_UNSUPPORTED -> DeviceErrorCodes.BG_ACTION_UNSUPPORTED
            else -> null
        }

    private fun displayErrorCode(code: String): String =
        when (code) {
            BackgroundDisplayWire.ERR_DISPLAY_UNAVAILABLE,
            BackgroundDisplayWire.ERR_AUTOMATION_UNAVAILABLE,
            -> DeviceErrorCodes.BG_RELEASED
            BackgroundDisplayWire.ERR_CAPTURE_FAILED -> DeviceErrorCodes.SCREENSHOT_FAILED
            BackgroundDisplayWire.ERR_FRAME_NOT_READY -> DeviceErrorCodes.BG_FRAME_NOT_READY
            BackgroundDisplayWire.ERR_TREE_UNAVAILABLE -> DeviceErrorCodes.APP_TREE_EMPTY
            BackgroundDisplayWire.ERR_LAUNCH_FAILED -> DeviceErrorCodes.LAUNCH_FAILED
            BackgroundDisplayWire.ERR_APP_ON_OTHER_DISPLAY -> DeviceErrorCodes.BG_APP_ON_OTHER_DISPLAY
            BackgroundDisplayWire.ERR_TARGET_CHANGED,
            BackgroundDisplayWire.ERR_FOREGROUND_UNAVAILABLE,
            -> DeviceErrorCodes.BG_TARGET_CHANGED
            BackgroundDisplayWire.ERR_LOCKED -> DeviceErrorCodes.BG_LOCKED
            BackgroundDisplayWire.ERR_TIMEOUT -> DeviceErrorCodes.TIMEOUT
            BackgroundDisplayWire.ERR_DISPLAY_CREATE_FAILED -> DeviceErrorCodes.BG_DISPLAY_CREATE_FAILED
            else -> DeviceErrorCodes.ACTION_FAILED
        }

    private companion object {
        val TERMINAL_HOST_ERRORS =
            setOf(
                BackgroundDisplayWire.ERR_TARGET_CHANGED,
                BackgroundDisplayWire.ERR_FOREGROUND_UNAVAILABLE,
                BackgroundDisplayWire.ERR_DISPLAY_UNAVAILABLE,
                BackgroundDisplayWire.ERR_LOCKED,
            )
    }
}

/** Run 级后台显示会话；默认关闭，任何后台失败均不返回真实屏目标。 */
@Singleton
public class BackgroundDisplayManagerImpl
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val connector: ShellHostConnector,
        dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : RunDisplayController {
        private val mutex = Mutex()
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        private val sessions = HashMap<RunId, BackgroundDisplaySession>()
        private val creating = HashMap<RunId, CompletableDeferred<Outcome<BackgroundDisplaySession>>>()
        private val released = HashSet<RunId>()
        internal val releasedOwners: Set<RunId>
            get() = released
        private var hostClient: ShellHostClient? = null
        private var hostConnecting: CompletableDeferred<ShellHostClient>? = null
        private var hostGeneration = 0L

        override suspend fun open(
            owner: RunId,
            backgroundScreen: Boolean,
        ): Outcome<DisplayTarget> {
            if (!backgroundScreen) return Outcome.Ok(DisplayTarget.Main)
            val snapshot = configProvider.current()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return Outcome.Err(
                    AppError(DeviceErrorCodes.BG_UNSUPPORTED_SDK, "background screen requires Android 14"),
                )
            }
            return try {
                val pending = sessionOrCreate(owner, snapshot[DeviceConfig.BACKGROUND])
                when (val result = pending.await()) {
                    is Outcome.Ok -> Outcome.Ok(result.value.target)
                    is Outcome.Err -> result
                }
            } catch (e: CancellationException) {
                // 创建在 manager 作用域中继续到确定结果，避免取消调用方后留下无人回收的显示。
                withContext(NonCancellable) { release(owner) }
                throw e
            }
        }

        private suspend fun sessionOrCreate(
            owner: RunId,
            config: DeviceBackgroundSection,
        ): CompletableDeferred<Outcome<BackgroundDisplaySession>> =
            mutex.withLock {
                sessions[owner]?.takeUnless { it.released }?.let {
                    return@withLock CompletableDeferred(Outcome.Ok(it))
                }
                creating[owner]?.takeIf { owner in released }?.let {
                    val error = Outcome.Err(AppError(DeviceErrorCodes.BG_RELEASED, "run released"))
                    it.complete(error)
                    return@withLock CompletableDeferred(error)
                }
                released.remove(owner)
                creating[owner] ?: CompletableDeferred<Outcome<BackgroundDisplaySession>>().also {
                    creating[owner] = it
                    scope.launch { create(owner, config, it) }
                }
            }

        internal suspend fun session(target: DisplayTarget.Background): Outcome<BackgroundDisplaySession> {
            val session = mutex.withLock { sessions[target.owner] }
            return session
                ?.takeIf { !it.released && it.target.displayId == target.displayId }
                ?.let { Outcome.Ok(it) }
                ?: Outcome.Err(
                    AppError(DeviceErrorCodes.BG_RELEASED, "background display session unavailable", abortRun = true),
                )
        }

        override suspend fun release(owner: RunId) {
            val session =
                mutex.withLock {
                    if (owner in creating) released.add(owner) else released.remove(owner)
                    sessions.remove(owner)
                }
            session?.release()
        }

        override suspend fun closeAll() {
            val (active, client) =
                mutex.withLock {
                    released.addAll(creating.keys)
                    hostGeneration += 1L
                    hostConnecting = null
                    val current = hostClient
                    hostClient = null
                    sessions.values.toList().also { sessions.clear() } to current
                }
            active.forEach { it.release() }
            if (client != null) {
                runCatching {
                    client.request(
                        JSONObject().put("op", BackgroundDisplayWire.OP_SHUTDOWN),
                        SHUTDOWN_TIMEOUT_MS,
                    )
                }
                client.close()
            }
        }

        @Suppress("TooGenericExceptionCaught") // 最后兜底必须完成创建 deferred，避免等待方永久挂起。
        private suspend fun create(
            owner: RunId,
            config: com.example.autop.core.config.DeviceBackgroundSection,
            result: CompletableDeferred<Outcome<BackgroundDisplaySession>>,
        ) {
            try {
                val outcome = openHost(owner, config)
                val (cancelled, lateSession) =
                    mutex.withLock {
                        creating.remove(owner)
                        val cancelled = released.remove(owner)
                        val lateSession =
                            if (cancelled) {
                                (outcome as? Outcome.Ok)?.value
                            } else {
                                (outcome as? Outcome.Ok)?.let { sessions[owner] = it.value }
                                null
                            }
                        cancelled to lateSession
                    }
                lateSession?.release()
                result.complete(
                    if (cancelled) {
                        Outcome.Err(AppError(DeviceErrorCodes.BG_RELEASED, "run released during display creation"))
                    } else {
                        outcome
                    },
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        creating.remove(owner)
                        released.remove(owner)
                    }
                }
                result.complete(
                    Outcome.Err(AppError(DeviceErrorCodes.BG_RELEASED, "display creation cancelled", cause = e)),
                )
                throw e
            } catch (e: Exception) {
                mutex.withLock { creating.remove(owner) }
                result.complete(
                    Outcome.Err(
                        AppError(
                            DeviceErrorCodes.BG_DISPLAY_CREATE_FAILED,
                            "background display creation failed",
                            cause = e,
                        ),
                    ),
                )
            }
        }

        private suspend fun openHost(
            owner: RunId,
            config: DeviceBackgroundSection,
        ): Outcome<BackgroundDisplaySession> =
            try {
                val client = acquireHost(config)
                openBackgroundDisplaySession(owner, config, client, logger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.w("BackgroundDisplay", "host_unavailable", "runId" to owner.value, error = e)
                Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.BG_CHANNEL_UNAVAILABLE,
                        message = "background host unavailable",
                        cause = e,
                    ),
                )
            }

        private suspend fun acquireHost(config: DeviceBackgroundSection): ShellHostClient {
            val pending =
                mutex.withLock {
                    hostClient?.takeIf { it.isAlive }?.let { return@withLock CompletableDeferred(it) }
                    hostConnecting ?: CompletableDeferred<ShellHostClient>().also { deferred ->
                        hostConnecting = deferred
                        val generation = hostGeneration
                        scope.launch {
                            val connected =
                                runCatching {
                                    connector.connectBackground(
                                        config,
                                        config.launchTimeoutMs,
                                        config.launchTimeoutMs,
                                    )
                                }
                            mutex.withLock {
                                if (hostConnecting === deferred) hostConnecting = null
                                if (generation != hostGeneration) {
                                    connected.getOrNull()?.close()
                                    deferred.completeExceptionally(IOException("background host closed during connect"))
                                } else {
                                    connected.fold(
                                        onSuccess = {
                                            hostClient = it
                                            deferred.complete(it)
                                        },
                                        onFailure = deferred::completeExceptionally,
                                    )
                                }
                            }
                        }
                    }
                }
            return pending.await()
        }
    }

private const val SHUTDOWN_TIMEOUT_MS = 1_000L
private const val RPC_GRACE_MS = 1_000L

@Suppress("TooGenericExceptionCaught") // 协议响应解析及隐藏 API 失败统一收口为稳定设备错误
internal suspend fun openBackgroundDisplaySession(
    owner: RunId,
    config: DeviceBackgroundSection,
    client: ShellHostClient,
    logger: Logger,
): Outcome<BackgroundDisplaySession> =
    try {
        val reply =
            client.request(
                JSONObject()
                    .put("op", BackgroundDisplayWire.OP_CREATE)
                    .put("sessionId", owner.value)
                    .put("width", config.width)
                    .put("height", config.height)
                    .put("dpi", config.dpi)
                    .put("jpegQuality", config.jpegQuality)
                    .put("maxPayloadBytes", config.maxPayloadBytes),
                config.launchTimeoutMs,
            )
        if (!reply.optBoolean("ok")) {
            Outcome.Err(AppError(DeviceErrorCodes.BG_DISPLAY_CREATE_FAILED, reply.optString("error")))
        } else {
            Outcome
                .Ok(
                    BackgroundDisplaySession(
                        DisplayTarget.Background(
                            owner,
                            reply.getInt("displayId").also { require(it > 0) },
                            reply.getInt("width"),
                            reply.getInt("height"),
                            reply.getInt("dpi"),
                        ),
                        client,
                        config,
                    ),
                )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        client.close()
        logger.w("BackgroundDisplay", "host_unavailable", "runId" to owner.value, error = e)
        Outcome.Err(
            AppError(
                code = DeviceErrorCodes.BG_CHANNEL_UNAVAILABLE,
                message = "background host unavailable",
                cause = e,
            ),
        )
    } catch (e: Exception) {
        client.close()
        logger.w("BackgroundDisplay", "create_failed", "runId" to owner.value, error = e)
        Outcome.Err(
            AppError(
                code = DeviceErrorCodes.BG_DISPLAY_CREATE_FAILED,
                message = "background display creation failed",
                cause = e,
            ),
        )
    }
