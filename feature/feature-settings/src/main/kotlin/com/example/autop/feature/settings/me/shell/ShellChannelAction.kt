/**
 * 注入通道子页用户操作（N200，docs/standards/coding-standards §8：sealed interface）。
 */
package com.example.autop.feature.settings.me.shell

public sealed interface ShellChannelAction {
    /** 配对端口编辑。 */
    public data class UpdatePort(
        val text: String,
    ) : ShellChannelAction

    /** 配对码编辑。 */
    public data class UpdateCode(
        val text: String,
    ) : ShellChannelAction

    /** 无线调试配对。 */
    public data object Pair : ShellChannelAction

    /** 手动连接注入通道（无线调试已开启时）。 */
    public data object Connect : ShellChannelAction

    /** Shell 执行总闸切换（开启会先弹知情同意）。 */
    public data class SetShellExec(
        val enabled: Boolean,
    ) : ShellChannelAction

    /** 知情同意：确认开启 Shell 执行。 */
    public data object ConfirmShellExecConsent : ShellChannelAction

    /** 知情同意：取消，保持关闭。 */
    public data object DismissShellExecConsent : ShellChannelAction
}
