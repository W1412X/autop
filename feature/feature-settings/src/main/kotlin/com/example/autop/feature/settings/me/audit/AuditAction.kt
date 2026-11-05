/**
 * 操作记录页用户操作（docs/standards/coding-standards §8）。
 */
package com.example.autop.feature.settings.me.audit

public sealed interface AuditAction {
    /** 加载下一页（末页后不再加载）。 */
    public data object LoadMore : AuditAction

    /** 从头重新加载。 */
    public data object Refresh : AuditAction
}
