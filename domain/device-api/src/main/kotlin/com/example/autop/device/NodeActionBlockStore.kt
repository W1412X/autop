/**
 * 节点动作屏蔽记录（ADR-0015）：部分应用接受无障碍节点动作却不响应，或直接拒绝。
 * 按包名记录这类应用，之后对它们的点击、长按、滚动直接使用手势。
 */
package com.example.autop.device

/** 一条屏蔽记录。 */
public data class NodeActionBlock(
    val packageName: String,
    /** 判定依据的简短英文标识，如 `rejected_gesture_ok`、`ignored_gesture_ok`。 */
    val reason: String,
    /** 记录时间（毫秒）。 */
    val blockedAt: Long,
)

/**
 * 节点动作屏蔽记录的存取端口。
 *
 * 线程：所有方法主线程安全。记录有有效期（`device.nodeAction.blockTtlDays`），过期视为未屏蔽。
 */
public interface NodeActionBlockStore {
    /** [packageName] 是否被记录为屏蔽了节点动作（未过期）。 */
    public suspend fun isBlocked(packageName: String): Boolean

    /** 记录 [packageName] 屏蔽了节点动作；已有记录则刷新时间。 */
    public suspend fun block(
        packageName: String,
        reason: String,
    )

    /** 清除 [packageName] 的记录（用户手动恢复或应用更新后重试）。 */
    public suspend fun unblock(packageName: String)

    /** 当前有效的全部记录。 */
    public suspend fun blocked(): List<NodeActionBlock>
}
