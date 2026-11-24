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

    /** 编辑后台虚拟屏参数；null 表示沿用已保存值。 */
    public data class UpdateBackgroundConfig(
        val width: String? = null,
        val height: String? = null,
        val dpi: String? = null,
        val launchTimeoutMs: String? = null,
        val requestTimeoutMs: String? = null,
    ) : ShellChannelAction

    /** 保存后台虚拟屏高级参数；下次创建显示会话时生效。 */
    public data object SaveBackgroundConfig : ShellChannelAction

    /** 知情同意：取消，保持关闭。 */
    public data object DismissShellExecConsent : ShellChannelAction
}
