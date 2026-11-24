package com.example.autop.device.impl.shell

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.device.ShellExec
import com.example.autop.device.ShellExecErrorCodes
import com.example.autop.device.ShellExecResult
import com.example.autop.device.ShellJobState
import com.example.autop.device.impl.shell.host.ShellHostWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShellPool"

/** 空闲回收检查的间隔（实现细节，不是配置项）。 */
private const val REAP_TICK_MS = 30_000L

/**
 * shell 执行端口的实现（shell-exec 设计 §5、ADR-0042）。
 *
 * - 每个 Run 最多一个宿主（[HostEntry]），不同 Run 的宿主互不共享；
 * - 宿主数量受全局上限约束（[ShellExecLimits.hostPoolSize]），满时先回收空闲宿主；
 * - 同一 Run 的并发调用共享宿主，各自占用一个 job 槽（[ShellExecLimits.maxJobsPerRun]）；
 * - 同一 Run 的宿主创建是单飞的：并行调用只握手一次。
 *
 * 锁：[mutex] 只保护 [hosts] 与 [creating] 两张表，不跨越任何挂起的网络操作。
 */
@Singleton
public class ShellPool
    @Inject
    constructor(
        private val connector: ShellHostConnector,
        private val dispatchers: DispatcherProvider,
        private val clock: Clock,
        private val logger: Logger,
        private val limits: ShellExecLimits,
    ) : ShellExec {
        private val mutex = Mutex()
        private val hosts = HashMap<RunId, HostEntry>()
        private val creating = HashMap<RunId, CompletableDeferred<Outcome<HostEntry>>>()
        private val released = HashSet<RunId>()
        private val hostPermits = Semaphore(limits.hostPoolSize)
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        private var reaper: Job? = null

        override suspend fun exec(
            owner: RunId,
            command: String,
            timeoutMs: Long,
        ): Outcome<ShellExecResult> {
            validate(command, timeoutMs)?.let { return Outcome.Err(it) }
            val entry =
                when (val acquired = acquireHost(owner)) {
                    is Outcome.Err -> return acquired
                    is Outcome.Ok -> acquired.value
                }
            if (withTimeoutOrNull(limits.jobSlotWaitMs) { entry.jobSlots.acquire() } == null) {
                return busy("job slots exhausted for this run")
            }
            try {
                return runJob(owner, entry, command, timeoutMs)
            } finally {
                entry.jobSlots.release()
                entry.lastUsedMs = clock.nowMillis()
            }
        }

        override suspend fun releaseRun(owner: RunId) {
            mutex.withLock {
                released.add(owner)
                hosts.remove(owner)?.let { dropLocked(it) }
            }
        }

        override suspend fun closeAll() {
            mutex.withLock {
                hosts.values.forEach { dropLocked(it) }
                hosts.clear()
                released.addAll(creating.keys)
            }
        }

        private suspend fun runJob(
            owner: RunId,
            entry: HostEntry,
            command: String,
            timeoutMs: Long,
        ): Outcome<ShellExecResult> {
            val started = clock.nowMillis()
            var jobId: String? = null
            try {
                val startReply = entry.client.request(ShellHostRequests.start(command, timeoutMs), limits.rpcTimeoutMs)
                if (startReply.optBoolean("ok").not()) {
                    return startFailure(startReply.optString("error"))
                }
                val id = startReply.getString("jobId")
                jobId = id
                var reply = entry.client.request(ShellHostRequests.wait(id, limits.waitChunkMs), waitRpcTimeout())
                while (reply.optString("state") == ShellHostWire.STATE_RUNNING) {
                    reply = entry.client.request(ShellHostRequests.wait(id, limits.waitChunkMs), waitRpcTimeout())
                }
                return Outcome.Ok(toResult(reply))
            } catch (e: CancellationException) {
                cancelRemote(entry, jobId)
                throw e
            } catch (e: IOException) {
                logger.w(TAG, "shell_host_transport_failed", "runCommand" to true, error = e)
                dropHost(owner, entry)
                return Outcome.Ok(transportFailed(clock.nowMillis() - started))
            }
        }

        private fun waitRpcTimeout(): Long = limits.rpcTimeoutMs + limits.waitChunkMs

        private suspend fun cancelRemote(
            entry: HostEntry,
            jobId: String?,
        ) {
            if (jobId == null) return
            withContext(NonCancellable) {
                runCatching { entry.client.request(ShellHostRequests.cancel(jobId), limits.rpcTimeoutMs) }
            }
        }

        private fun startFailure(code: String): Outcome<ShellExecResult> =
            when (code) {
                ShellHostWire.ERR_BUSY -> busy("host job slots exhausted")
                ShellHostWire.ERR_BAD_REQUEST -> invalid("host rejected the command")
                else ->
                    Outcome.Err(
                        AppError(code = ShellExecErrorCodes.UNAVAILABLE, message = "shell host failed to start a job"),
                    )
            }

        private fun toResult(reply: JSONObject): ShellExecResult {
            val state =
                when (reply.optString("state")) {
                    ShellHostWire.STATE_DONE -> ShellJobState.DONE
                    ShellHostWire.STATE_TIMED_OUT -> ShellJobState.TIMED_OUT
                    ShellHostWire.STATE_CANCELLED -> ShellJobState.CANCELLED
                    else -> ShellJobState.TRANSPORT_FAILED
                }
            return ShellExecResult(
                state = state,
                exitCode = if (reply.isNull("exitCode")) null else reply.optInt("exitCode"),
                stdout = reply.optString("stdout"),
                stderr = reply.optString("stderr"),
                truncated = reply.optBoolean("truncated"),
                omittedBytes = reply.optLong("omittedBytes"),
                durationMs = reply.optLong("durationMs"),
            )
        }

        private fun transportFailed(durationMs: Long): ShellExecResult =
            ShellExecResult(
                state = ShellJobState.TRANSPORT_FAILED,
                exitCode = null,
                stdout = "",
                stderr = "",
                truncated = false,
                omittedBytes = 0L,
                durationMs = durationMs,
            )

        private suspend fun acquireHost(owner: RunId): Outcome<HostEntry> {
            val pending =
                mutex.withLock {
                    hosts[owner]?.takeIf { it.client.isAlive }?.let { return Outcome.Ok(it) }
                    hosts.remove(owner)?.let { dropLocked(it) }
                    creating[owner]?.takeIf { owner in released }?.let {
                        it.complete(Outcome.Err(AppError(ShellExecErrorCodes.UNAVAILABLE, "run released")))
                        return Outcome.Err(AppError(ShellExecErrorCodes.UNAVAILABLE, "run released"))
                    }
                    released.remove(owner)
                    creating[owner] ?: CompletableDeferred<Outcome<HostEntry>>().also { deferred ->
                        creating[owner] = deferred
                        scope.launch { create(owner, deferred) }
                    }
                }
            return pending.await()
        }

        private suspend fun create(
            owner: RunId,
            result: CompletableDeferred<Outcome<HostEntry>>,
        ) {
            val outcome = openHost(owner)
            mutex.withLock {
                creating.remove(owner)
                if (owner in released) {
                    (outcome as? Outcome.Ok)?.value?.let { dropLocked(it) }
                    result.complete(
                        Outcome.Err(AppError(ShellExecErrorCodes.UNAVAILABLE, "run released during host creation")),
                    )
                    return
                }
                (outcome as? Outcome.Ok)?.let { hosts[owner] = it.value }
            }
            ensureReaper()
            result.complete(outcome)
        }

        private suspend fun openHost(owner: RunId): Outcome<HostEntry> {
            if (!hostPermits.tryAcquire()) {
                closeIdleHosts()
                if (withTimeoutOrNull(limits.hostAcquireWaitMs) { hostPermits.acquire() } == null) {
                    return busy("shell host pool exhausted")
                }
            }
            return try {
                val client = connector.connect(limits)
                Outcome.Ok(HostEntry(owner, client, Semaphore(limits.maxJobsPerRun), clock.nowMillis()))
            } catch (e: IOException) {
                hostPermits.release()
                logger.w(TAG, "shell_host_unavailable", error = e)
                Outcome.Err(AppError(code = ShellExecErrorCodes.UNAVAILABLE, message = "shell host unavailable"))
            } catch (e: CancellationException) {
                hostPermits.release()
                throw e
            }
        }

        private suspend fun dropHost(
            owner: RunId,
            entry: HostEntry,
        ) {
            mutex.withLock {
                if (hosts[owner] === entry) {
                    hosts.remove(owner)
                    dropLocked(entry)
                }
            }
        }

        private suspend fun closeIdleHosts() {
            mutex.withLock {
                hosts.values
                    .filter { it.jobSlots.availablePermits == limits.maxJobsPerRun }
                    .forEach { entry ->
                        hosts.remove(entry.owner)
                        dropLocked(entry)
                    }
            }
        }

        /** 必须在 [mutex] 内调用。关闭宿主并归还一个宿主名额。 */
        private fun dropLocked(entry: HostEntry) {
            entry.client.close()
            hostPermits.release()
        }

        private suspend fun ensureReaper() {
            mutex.withLock {
                if (reaper == null) {
                    reaper =
                        scope.launch {
                            while (true) {
                                delay(REAP_TICK_MS)
                                reapIdle()
                            }
                        }
                }
            }
        }

        private suspend fun reapIdle() {
            mutex.withLock {
                val now = clock.nowMillis()
                hosts.values
                    .filter { it.jobSlots.availablePermits == limits.maxJobsPerRun }
                    .filter { now - it.lastUsedMs >= limits.hostIdleCloseMs }
                    .forEach { entry ->
                        hosts.remove(entry.owner)
                        dropLocked(entry)
                    }
            }
        }

        private fun validate(
            command: String,
            timeoutMs: Long,
        ): AppError? {
            if (timeoutMs <= 0L) return invalidError("timeout must be positive")
            val problem = ShellCommandRules.problemOf(command, limits.commandMaxBytes) ?: return null
            return invalidError(problem)
        }

        private fun <T> invalid(message: String): Outcome<T> = Outcome.Err(invalidError(message))

        private fun invalidError(message: String): AppError =
            AppError(code = ShellExecErrorCodes.INVALID_ARGUMENT, message = message)

        private fun <T> busy(message: String): Outcome<T> =
            Outcome.Err(AppError(code = ShellExecErrorCodes.BUSY, message = message))

        /** 一个 Run 的宿主与其 job 槽。 */
        private class HostEntry(
            val owner: RunId,
            val client: ShellHostClient,
            val jobSlots: Semaphore,
            @Volatile var lastUsedMs: Long,
        )
    }
