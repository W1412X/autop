/**
 * 智能体按需占用手机（ADR-0035）：Run 默认不占用屏幕，首次调用读取或操作屏幕的工具时才取得。
 * 实例与单个 Run 绑定；取得后持有到 Run 结束。agent 层只依赖本端口，不依赖设备领域。
 */
package com.example.autop.agent

/** 屏幕门：[acquire] 的结果。 */
public enum class ScreenAccess {
    /** 已持有（本次取得或之前已取得）。 */
    HELD,

    /** 手机正被其他任务使用，本次取不到。 */
    BUSY,
}

/** 按需占用屏幕的门（每个 Run 一个实例）。 */
public interface ScreenAccessGate {
    /** 取得屏幕：未持有时尝试取得（有超时），已持有直接返回 [ScreenAccess.HELD]。 */
    public suspend fun acquire(): ScreenAccess
}
