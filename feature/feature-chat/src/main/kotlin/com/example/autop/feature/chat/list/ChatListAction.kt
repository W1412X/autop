/**
 * 对话列表页用户操作（docs/standards/coding-standards §8：sealed interface，
 * 用户操作统一通过 `onAction(action)` 交给 ViewModel；导航类动作由 NavGraph 的
 * Route 层拦截后转 [com.example.autop.feature.chat.ChatNavigator]）。
 */
package com.example.autop.feature.chat.list

public sealed interface ChatListAction {
    /** 新建对话（进入"新对话"页）。 */
    public data object NewChat : ChatListAction

    /** 打开对话页。 */
    public data class OpenThread(
        val threadId: String,
    ) : ChatListAction

    /** "去开启"无障碍（跳系统无障碍设置）。 */
    public data object EnableAccessibility : ChatListAction

    /** 长按行：打开行菜单。 */
    public data class ShowThreadMenu(
        val threadId: String,
    ) : ChatListAction

    /** 关闭行菜单。 */
    public data object DismissMenu : ChatListAction

    /** 行菜单选"重命名"。 */
    public data class ShowRename(
        val threadId: String,
    ) : ChatListAction

    /** 重命名提交。 */
    public data class Rename(
        val threadId: String,
        val title: String,
    ) : ChatListAction

    /** 关闭重命名输入框（不提交）。 */
    public data object DismissRename : ChatListAction

    /** 行菜单选"删除"：弹确认框。 */
    public data class ShowDelete(
        val threadId: String,
    ) : ChatListAction

    /** 确认删除。 */
    public data object ConfirmDelete : ChatListAction

    /** 取消删除。 */
    public data object DismissDelete : ChatListAction

    /** 停止运行中的 Run。 */
    public data object StopActive : ChatListAction

    /** 分页加载下一页。 */
    public data object LoadMore : ChatListAction
}
