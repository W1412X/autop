package com.example.autop.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * 统一结果类型（docs/tech/02 §1.1）。
 *
 * 对外 API 一律返回 `Outcome`，不用异常表达业务失败；错误码来自某个 `*ErrorCodes` 常量。
 */
public sealed interface Outcome<out T> {
    public data class Ok<out T>(
        val value: T,
    ) : Outcome<T>

    public data class Err(
        val error: AppError,
    ) : Outcome<Nothing>
}

/** 成功时变换值，失败原样传递。 */
public inline fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Ok -> Outcome.Ok(f(value))
        is Outcome.Err -> this
    }

/** 成功时进入下一个可能失败的步骤。 */
public inline fun <T, R> Outcome<T>.flatMap(f: (T) -> Outcome<R>): Outcome<R> =
    when (this) {
        is Outcome.Ok -> f(value)
        is Outcome.Err -> this
    }

/** 失败时观察错误（日志、埋点），链路不变。 */
public inline fun <T> Outcome<T>.onErr(f: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Err) f(error)
    return this
}

public fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/**
 * 捕获 [block] 中的 `Exception` 转为 [Outcome.Err]（错误码用 [code]），
 * **不吞** `CancellationException`——取消必须继续向上传播（代码规范 §7）。
 *
 * 之所以是 inline：lambda 体在挂起上下文中展开，`block` 内可以直接调用挂起函数。
 *
 * `TooGenericExceptionCaught` 是本函数的设计意图（统一兜底转换），故显式豁免一次。
 */
@Suppress("TooGenericExceptionCaught")
public suspend inline fun <T> runCatchingCancellable(
    code: String,
    block: () -> T,
): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(AppError(code = code, message = e.message ?: e.toString(), cause = e))
    }

/** 把异常转为 [AppError] 的便捷函数；取消原样抛出。 */
@Suppress("TooGenericExceptionCaught")
public inline fun <T> catchingAsError(
    code: String,
    block: () -> T,
): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(AppError(code = code, message = e.message ?: e.toString(), cause = e))
    }
