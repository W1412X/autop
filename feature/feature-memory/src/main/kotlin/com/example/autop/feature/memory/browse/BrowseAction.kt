/**
 * 记忆浏览页动作（docs/tech/08 §4.4）。
 */
package com.example.autop.feature.memory.browse

/** 浏览页用户动作。 */
public sealed interface BrowseAction {
    /** 点击面包屑跳转目录。 */
    public data class OpenCrumb(
        val index: Int,
    ) : BrowseAction

    /** 点击列表行（目录下钻、条目进编辑页）。 */
    public data class OpenRow(
        val path: String,
        val isDirectory: Boolean,
    ) : BrowseAction

    /** 搜索框输入（防抖后检索）。 */
    public data class QueryChanged(
        val query: String,
    ) : BrowseAction

    /** 分页加载下一页。 */
    public data object LoadMore : BrowseAction

    /** 删除行（长按/行菜单入口）：弹确认。 */
    public data class RequestDelete(
        val path: String,
    ) : BrowseAction

    /** 确认删除（目录整树递归删）。 */
    public data object ConfirmDelete : BrowseAction

    public data object DismissDelete : BrowseAction

    /** 重试加载。 */
    public data object Retry : BrowseAction
}
