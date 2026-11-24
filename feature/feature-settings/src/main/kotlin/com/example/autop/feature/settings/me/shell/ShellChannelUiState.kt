/**
 * 注入通道子页状态（N200，docs/tech/08 §4.5 `me/shell`）：无线调试配对（系统设置「使用配对码配对设备」弹窗里的
 * 配对端口与 6 位配对码）与连接状态；另含 Shell 执行总闸（shell-exec 设计 §2.5）。
 */
package com.example.autop.feature.settings.me.shell

import com.example.autop.core.config.DeviceBackgroundSection
import com.example.autop.device.ShellState

/** 配对提示（文案在 feature-settings 的 strings，ViewModel 不接触资源）。 */
public sealed interface ShellNotice {
    /** 配对输入不完整。 */
    public data object InputMissing : ShellNotice

    /** 配对失败（[message] 为底层错误描述）。 */
    public data class PairFailed(
        val message: String,
    ) : ShellNotice

    /** 后台参数格式或范围不合法。 */
    public data object BackgroundInvalid : ShellNotice

    /** 后台参数已保存。 */
    public data object BackgroundSaved : ShellNotice
}

/** 注入通道子页状态。 */
public data class ShellChannelUiState(
    /** 通道连接态。 */
    val shellState: ShellState = ShellState.Disconnected,
    /** 配对端口文本。 */
    val portText: String = "",
    /** 配对码文本。 */
    val codeText: String = "",
    /** 配对/连接进行中。 */
    val busy: Boolean = false,
    /** 后台自动搜索无线调试中（未配对且尚未发现端口）。 */
    val discovering: Boolean = false,
    /** 配对提示（null=无）。 */
    val notice: ShellNotice? = null,
    /** Shell 执行总闸是否开启（features.shellExec）。 */
    val shellExecEnabled: Boolean = false,
    /** 后台虚拟屏高级参数（随保存生效，不改变会话后台模式）。 */
    val backgroundConfig: DeviceBackgroundSection = DeviceBackgroundSection(),
    val backgroundWidthText: String = "720",
    val backgroundHeightText: String = "1600",
    val backgroundDpiText: String = "320",
    val backgroundLaunchTimeoutText: String = "4000",
    val backgroundRequestTimeoutText: String = "30000",
    /** 首次开启需要知情同意：弹窗显示中。 */
    val shellExecConsentPending: Boolean = false,
)
