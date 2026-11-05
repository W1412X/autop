/**
 * [ShellKeepAlive] 实现（N203）：周期巡逻——已配对且断开时自动重连
 * （connect 内部 mDNS 发现端口 → TLS 鉴权，与注入共用互斥锁）。
 * 连接成功后无需干预（断开由下一轮巡逻拾起）；未配对时空转，
 * 不反复 mDNS 打扰未开启无线调试的设备。
 */
package com.example.autop.device.impl.shell

import com.example.autop.device.ShellInput
import com.example.autop.device.ShellKeepAlive
import com.example.autop.device.ShellState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 巡逻间隔（已连接/未配对）。 */
private const val TICK_MS = 15_000L

/** 重连失败后的重试间隔。 */
private const val RETRY_MS = 30_000L

/** shell 通道保活器实现。 */
@Singleton
public class ShellKeepAliveImpl
    @Inject
    constructor(
        private val shellInput: ShellInput,
    ) : ShellKeepAlive {
        private var job: Job? = null

        @Synchronized
        override fun start(scope: CoroutineScope) {
            if (job?.isActive == true) return
            job = scope.launch { loop() }
        }

        @Synchronized
        override fun stop() {
            job?.cancel()
            job = null
        }

        /** 周期循环：已配对且断开 → 重连；成功长间隔巡逻，失败短间隔重试。 */
        private suspend fun loop() {
            while (currentCoroutineContext().isActive) {
                val reconnect = shellInput.paired.value && shellInput.state.value == ShellState.Disconnected
                val nextMs =
                    if (reconnect) {
                        if (shellInput.connect()) TICK_MS else RETRY_MS
                    } else {
                        TICK_MS
                    }
                delay(nextMs)
            }
        }
    }
