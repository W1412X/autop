package com.example.autop.core.testing

import com.example.autop.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 单一测试调度器的 [DispatcherProvider]（docs/tech/02 §5）：main/io/default 都指向注入的
 * [testDispatcher]（通常为 `StandardTestDispatcher`），保证测试确定性（任务书测试要求）。
 */
public class TestDispatcherProvider(
    private val testDispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher
        get() = testDispatcher

    override val io: CoroutineDispatcher
        get() = testDispatcher

    override val default: CoroutineDispatcher
        get() = testDispatcher
}
