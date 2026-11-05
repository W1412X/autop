/**
 * shell 注入通道后台保活（N203）：配对是一次性配置、连接需长期维持——
 * 无线调试重启/连接断开后自动重连，保证后台触发的 Run 注入通道就绪，
 * 不必等用户手动点“连接”。
 *
 * 由 AgentHost 前台服务驱动启停（docs/tech/07 §4.5）。
 */
package com.example.autop.device

import kotlinx.coroutines.CoroutineScope

/** shell 通道保活器。 */
public interface ShellKeepAlive {
    /** 在 [scope] 内启动保活循环（幂等，已在运行时无操作）。 */
    public fun start(scope: CoroutineScope)

    /** 停止保活循环。 */
    public fun stop()
}
