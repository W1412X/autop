/**
 * 条目编辑页动作（docs/tech/08 §4.4）。
 */
package com.example.autop.feature.memory.node

/** 可编辑字段。 */
public enum class NodeField {
    TITLE,
    SUMMARY,
    CONTENT,
}

/** 条目编辑页用户动作。 */
public sealed interface NodeAction {
    /** 字段编辑。 */
    public data class FieldChanged(
        val field: NodeField,
        val value: String,
    ) : NodeAction

    /** 添加关键词（只用于检索）。 */
    public data class KeywordAdded(
        val keyword: String,
    ) : NodeAction

    /** 删除关键词。 */
    public data class KeywordRemoved(
        val keyword: String,
    ) : NodeAction

    /** 保存（新建走 CREATE，已存在走 UPDATE）。 */
    public data object Save : NodeAction

    /** 打开 / 关闭版本历史。 */
    public data object ShowVersions : NodeAction

    public data object HideVersions : NodeAction

    /** 点开某版本的前后对比。 */
    public data class ShowDiff(
        val versionId: Long,
    ) : NodeAction

    public data object HideDiff : NodeAction

    /** 恢复到某版本（撤销该版本对应的变化）。 */
    public data class Revert(
        val versionId: Long,
    ) : NodeAction

    /** 确认 agent 写入（标记为用户内容）。 */
    public data object Confirm : NodeAction

    /** 撤销 agent 的此次写入。 */
    public data object UndoLastWrite : NodeAction

    /** 更多菜单（移动 / 删除）。 */
    public data object ShowMore : NodeAction

    public data object HideMore : NodeAction

    /** 移动到 [target] 路径。 */
    public data class Move(
        val target: String,
    ) : NodeAction

    /** 打开 / 关闭移动输入框。 */
    public data object ShowMove : NodeAction

    public data object HideMove : NodeAction

    /** 删除确认。 */
    public data object Delete : NodeAction

    public data object ConfirmDelete : NodeAction

    public data object DismissDelete : NodeAction

    /** 重试加载。 */
    public data object Retry : NodeAction
}
