package com.example.autop.core.platform

import com.example.autop.core.common.Logger

/**
 * 进程级未捕获异常留痕（docs/tech/02 §4.4，questions.md N165）：
 * 线程未捕获异常先记 `uncaught_exception`（含堆栈，随日志落文件），再交还原 handler——
 * 不吞崩溃、不改系统崩溃语义，只为事后取证留痕（真机闪退后文件是唯一线索）。
 */
public object CrashLogger {
    private const val TAG: String = "CrashLogger"
    private const val EVENT: String = "uncaught_exception"

    /** 安装全局兜底（Application.onCreate 最早调用）；重复安装跳过，避免叠包双写。 */
    public fun install(logger: Logger) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(logger, previous))
    }

    /** 实际 handler：留痕后转交前序 handler（Android 上必有 RuntimeInit 的默认实现）。 */
    private class Handler(
        private val logger: Logger,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            thread: Thread,
            error: Throwable,
        ) {
            runCatching { logger.e(TAG, EVENT, "thread" to thread.name, error = error) }
            previous?.uncaughtException(thread, error) ?: throw error
        }
    }
}
