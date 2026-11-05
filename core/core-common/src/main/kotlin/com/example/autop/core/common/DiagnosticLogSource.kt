package com.example.autop.core.common

/**
 * 诊断日志读取端口（docs/tech/02 §4.4）：环形内存缓冲快照，
 * 供"设置 → 高级 → 诊断日志"页查看和复制（实现见 core-platform 的 AndroidLogger）。
 */
public interface DiagnosticLogSource {
    /** 当前缓冲快照（最旧在前）。 */
    public fun snapshot(): List<String>
}
