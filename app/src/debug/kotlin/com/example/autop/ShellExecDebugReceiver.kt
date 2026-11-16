/**
 * shell 执行真机验证入口（shell-exec 设计 §14 第 2 步，仅 debug 构建）。
 *
 * 用 adb 广播触发，结果写入应用私有目录 files/shell-debug/，用 `adb shell run-as` 读取（仅 debug 可用）。
 * 输出只写到这个调试文件，不写普通日志（与正式路径的日志规则一致）。
 *
 * 动作与参数：
 * - `com.example.autop.debug.SHELL_EXEC`：`--es cmd "<命令>"`，可选 `--es owner <运行标识>`（默认 debug-run）、
 *   `--ei timeout <毫秒>`（默认 30000）、`--es name <结果文件名后缀>`。
 * - `com.example.autop.debug.SHELL_RELEASE`：`--es owner <运行标识>`，回收该标识的宿主。
 * - `com.example.autop.debug.SHELL_CLOSE`：回收全部宿主。
 *
 * 注意：`goAsync` 的时间有限，单次验证命令的时限应控制在 20 秒以内。
 */
package com.example.autop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.device.ShellExec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val ACTION_EXEC = "com.example.autop.debug.SHELL_EXEC"
private const val ACTION_RELEASE = "com.example.autop.debug.SHELL_RELEASE"
private const val ACTION_CLOSE = "com.example.autop.debug.SHELL_CLOSE"
private const val EXTRA_CMD = "cmd"
private const val EXTRA_OWNER = "owner"
private const val EXTRA_TIMEOUT = "timeout"
private const val EXTRA_NAME = "name"
private const val DEFAULT_OWNER = "debug-run"
private const val DEFAULT_TIMEOUT_MS = 30_000L
private const val RESULT_DIR = "shell-debug"

/** 调试用的 shell 执行接收器（仅 debug 构建，见 app/src/debug/AndroidManifest.xml）。 */
@AndroidEntryPoint
public class ShellExecDebugReceiver : BroadcastReceiver() {
    @Inject
    lateinit var shellExec: ShellExec

    @Inject
    lateinit var dispatchers: DispatcherProvider

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        scope.launch {
            try {
                val report =
                    when (intent.action) {
                        ACTION_EXEC -> exec(intent)
                        ACTION_RELEASE -> {
                            shellExec.releaseRun(RunId(intent.getStringExtra(EXTRA_OWNER) ?: DEFAULT_OWNER))
                            "released"
                        }
                        ACTION_CLOSE -> {
                            shellExec.closeAll()
                            "closed all hosts"
                        }
                        else -> "unknown action ${intent.action}"
                    }
                write(context, intent, report)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun exec(intent: Intent): String {
        val command = intent.getStringExtra(EXTRA_CMD).orEmpty()
        val owner = RunId(intent.getStringExtra(EXTRA_OWNER) ?: DEFAULT_OWNER)
        // adb 的 --ei 传入 Int，--el 传入 Long；两种都接受，避免 getLongExtra 对 Int 类型静默回退到默认值。
        val timeoutMs = (intent.extras?.get(EXTRA_TIMEOUT) as? Number)?.toLong() ?: DEFAULT_TIMEOUT_MS
        return when (val outcome = shellExec.exec(owner, command, timeoutMs)) {
            is Outcome.Err -> "error code=${outcome.error.code} message=${outcome.error.message}"
            is Outcome.Ok -> {
                val r = outcome.value
                buildString {
                    appendLine("state=${r.state} exitCode=${r.exitCode} durationMs=${r.durationMs}")
                    appendLine("truncated=${r.truncated} omittedBytes=${r.omittedBytes}")
                    appendLine("--- stdout ---")
                    appendLine(r.stdout)
                    appendLine("--- stderr ---")
                    append(r.stderr)
                }
            }
        }
    }

    private fun write(
        context: Context,
        intent: Intent,
        report: String,
    ) {
        val dir = File(context.filesDir, RESULT_DIR).apply { mkdirs() }
        val suffix =
            intent.getStringExtra(EXTRA_NAME) ?: intent.action?.substringAfterLast('_')?.lowercase() ?: "result"
        File(dir, "result-${System.nanoTime()}-$suffix.txt").writeText("action=${intent.action}\n$report\n")
    }
}
