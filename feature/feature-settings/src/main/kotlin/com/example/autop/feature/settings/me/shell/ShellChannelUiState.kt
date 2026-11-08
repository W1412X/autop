/**
 * 注入通道子页状态（N200，docs/tech/08 §4.5 `me/shell`）：无线调试配对
 * （系统设置「使用配对码配对设备」弹窗里的配对端口与 6 位配对码）与连接状态。
 */
package com.example.autop.feature.settings.me.shell

import com.example.autop.device.ShellState

/** 配对提示（文案在 feature-settings 的 strings，ViewModel 不接触资源）。 */
public sealed interface ShellNotice {
    /** 配对输入不完整。 */
    public data object InputMissing : ShellNotice

    /** 配对失败（[message] 为底层错误描述）。 */
    public data class PairFailed(
        val message: String,
    ) : ShellNotice
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
    /** 后台自动搜索可配对设备中（未配对且尚未发现端口）。 */
    val discovering: Boolean = false,
    /** 配对提示（null=无）。 */
    val notice: ShellNotice? = null,
)
