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
}
