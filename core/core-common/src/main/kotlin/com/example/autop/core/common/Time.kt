package com.example.autop.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 时间源（docs/tech/02 §1.3）。业务代码禁止直接取系统时间（代码规范 §5），
 * 一律通过注入的 `Clock`，测试用 `FakeClock`（core-testing）。
 */
public interface Clock {
    /** 墙钟毫秒（Unix epoch）。仅用于时间戳与排序，不用于测量耗时。 */
    public fun nowMillis(): Long
}

/** 进程内默认实现：系统墙钟。无状态，由 DI 提供为单例。 */
public class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * 协程调度器注入点（docs/tech/02 §1.3）。禁止 `GlobalScope` 与硬编码 `Dispatchers.*`
 * （任务书硬性规则 5），测试注入 `TestDispatcherProvider`（core-testing）。
 */
public interface DispatcherProvider {
    public val main: CoroutineDispatcher
    public val io: CoroutineDispatcher
    public val default: CoroutineDispatcher
}
