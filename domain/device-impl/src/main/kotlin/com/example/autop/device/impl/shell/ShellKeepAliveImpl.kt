/**
 * [ShellKeepAlive] 实现（N203）：周期巡逻——已配对且断开时自动重连
 * （connect 内部 mDNS 发现端口 → TLS 鉴权，与注入共用互斥锁）。
 * 连接成功后无需干预（断开由下一轮巡逻拾起）；未配对时空转，
 * 不反复 mDNS 打扰未开启无线调试的设备。
 * 巡逻间隔与重试间隔来自 `device.shell`（ADR-0037），每轮重新读取，改配置即时生效。
 */
package com.example.autop.device.impl.shell

import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
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

/** shell 通道保活器实现。 */
@Singleton
public class ShellKeepAliveImpl
    @Inject
    constructor(
        private val shellInput: ShellInput,
        private val configProvider: ConfigProvider,
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

        /** 周期循环：已连接则探测；已配对且断开则重连（失败走重试间隔）；其余按巡逻间隔空转。 */
        private suspend fun loop() {
            while (currentCoroutineContext().isActive) {
                val cfg = configProvider.current()[DeviceConfig.SHELL]
                val nextMs =
                    when {
                        // 已连接：每个周期探测一次。无线调试关闭后连接失效，探测会回落 Disconnected，下一轮重连
                        shellInput.state.value == ShellState.Connected -> {
                            shellInput.probe()
                            cfg.keepAliveTickMs
                        }
                        shellInput.paired.value && shellInput.state.value == ShellState.Disconnected ->
                            if (shellInput.connect()) cfg.keepAliveTickMs else cfg.keepAliveRetryMs
                        else -> cfg.keepAliveTickMs
                    }
                delay(nextMs)
            }
        }
    }
