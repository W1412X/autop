/**
 * 协程统一异常兜底（docs/tech/02 §4.4，questions.md N165）：
 * 所有自建 [CoroutineScope] 经 [exceptionHandler] 挂统一处理器，
 * 未捕获异常记 `coroutine_crashed`（含堆栈，随日志落文件）后不再传播到默认处理器，
 * 避免一个子协程错误杀死进程、或错误彻底无痕。
 */
package com.example.autop.core.common

import kotlinx.coroutines.CoroutineExceptionHandler

private const val TAG = "CoroutineGuard"
private const val EVENT_COROUTINE_CRASHED = "coroutine_crashed"

/**
 * 为自建作用域构造统一异常兜底：记录 `coroutine_crashed`（`scope` 标注来源，
 * 堆栈随 error 输出），SupervisorJob 兄弟协程不受影响。
 */
public fun Logger.exceptionHandler(scopeName: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        e(TAG, EVENT_COROUTINE_CRASHED, "scope" to scopeName, error = error)
    }
