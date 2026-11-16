package com.example.autop.device.impl.shell

import com.example.autop.core.config.DeviceShellExecSection

/**
 * shell 执行的资源参数（shell-exec 设计 §9.1）。
 *
 * 配置接入（`device.shell.exec.*` 段、assets 默认值、schema 版本）属于设计第 14 节第 6 步，
 * 在此之前由 DI 以这里的默认值提供。接入配置时只需替换 DI 提供的实例，调用方不变。
 */
public fun shellExecLimitsOf(section: DeviceShellExecSection): ShellExecLimits =
    ShellExecLimits(
        hostPoolSize = section.hostPoolSize,
        maxJobsPerRun = section.maxJobsPerRun,
        defaultTimeoutMs = section.defaultTimeoutMs,
        outputLimitBytes = section.outputLimitBytes,
        commandMaxBytes = section.commandMaxBytes,
        hostAcquireWaitMs = section.hostAcquireWaitMs,
        jobSlotWaitMs = section.jobSlotWaitMs,
        hostIdleCloseMs = section.hostIdleCloseMs,
        rpcTimeoutMs = section.rpcTimeoutMs,
        handshakeTimeoutMs = section.handshakeTimeoutMs,
        ackTimeoutMs = section.ackTimeoutMs,
        killGraceMs = section.killGraceMs,
        waitChunkMs = section.waitChunkMs,
        confirmTimeoutMs = section.confirmTimeoutMs,
    )

public data class ShellExecLimits(
    val hostPoolSize: Int = 3,
    val maxJobsPerRun: Int = 4,
    val defaultTimeoutMs: Long = 30_000L,
    val outputLimitBytes: Int = 65_536,
    val commandMaxBytes: Int = 2048,
    val hostAcquireWaitMs: Long = 10_000L,
    val jobSlotWaitMs: Long = 10_000L,
    val hostIdleCloseMs: Long = 300_000L,
    val rpcTimeoutMs: Long = 5_000L,
    val handshakeTimeoutMs: Long = 5_000L,
    val ackTimeoutMs: Long = 5_000L,
    val killGraceMs: Long = 2_000L,
    /** 客户端每次 `wait` 的等待时长；结束前会重复等待，不做高频轮询。 */
    val waitChunkMs: Long = 5_000L,
    /** 危险操作确认的等待上限（shell-exec 设计 §7.3），超时按拒绝处理。 */
    val confirmTimeoutMs: Long = 60_000L,
) {
    init {
        require(hostPoolSize >= 1 && maxJobsPerRun >= 1)
        require(outputLimitBytes >= MIN_OUTPUT_LIMIT_BYTES && commandMaxBytes >= 1)
    }

    private companion object {
        const val MIN_OUTPUT_LIMIT_BYTES = 4096
    }
}
