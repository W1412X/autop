/**
 * 节点操作页用户操作（docs/standards/coding-standards §8：sealed interface）。
 */
package com.example.autop.feature.settings.me.nodeaction

public sealed interface NodeActionAction {
    /** 切换总开关。 */
    public data class SetEnabled(
        val enabled: Boolean,
    ) : NodeActionAction

    /** 恢复某个应用的节点操作（清除屏蔽记录，下次重新尝试）。 */
    public data class Restore(
        val packageName: String,
    ) : NodeActionAction
}
