/**
 * 对话页用户操作（docs/standards/coding-standards §8：sealed interface，
 * 用户操作统一通过 `onAction(action)` 交给 ViewModel；导航类动作与一次性
 * 导航结果走 [ChatNavEvent]）。
 */
package com.example.autop.feature.chat.thread

import android.net.Uri
import com.example.autop.agent.timeline.StepLink

public sealed interface ChatThreadAction {
    /** 输入框变化。 */
    public data class SetInput(
        val text: String,
    ) : ChatThreadAction

    /** 从相册选中了图片（读取、压缩由 ViewModel 完成，超出上限的忽略）。 */
    public data class AddImages(
        val uris: List<Uri>,
    ) : ChatThreadAction

    /** 移除第 [index] 张待发送图片。 */
    public data class RemoveImage(
        val index: Int,
    ) : ChatThreadAction

    /** 切换本会话的模型（null = 跟随默认）。 */
    public data class SetModel(
        val profileId: String?,
    ) : ChatThreadAction

    /** 发送：空闲=发起对话；运行中=补充指令（steer）。 */
    public data object Send : ChatThreadAction

    /** 停止运行中的 Run。 */
    public data object Stop : ChatThreadAction

    /** 继续（暂停 / 拦截横幅的操作按钮）。 */
    public data object Resume : ChatThreadAction

    /** 回答 ask_user（选项 Chip 或自由输入）。 */
    public data class Answer(
        val text: String,
    ) : ChatThreadAction

    /** 长按时间线条目。 */
    public data class ShowEntryMenu(
        val seq: Long,
    ) : ChatThreadAction

    /** 元信息行"复制"图标：文本由 UI 写剪贴板，此动作仅提示"已复制"（N183）。 */
    public data object CopyReply : ChatThreadAction

    public data object DismissEntryMenu : ChatThreadAction

    /** "从这里分叉"（仅可 fork 点）。 */
    public data class ForkFrom(
        val seq: Long,
    ) : ChatThreadAction

    /** "查看原始数据"。 */
    public data class ShowRaw(
        val seq: Long,
    ) : ChatThreadAction

    public data object DismissRaw : ChatThreadAction

    /** 顶栏"更多"菜单。 */
    public data object ShowMoreMenu : ChatThreadAction

    public data object DismissMoreMenu : ChatThreadAction

    /** "保存为任务"（带会话摘录跳 AI 创建任务页）。 */
    public data object SaveAsTask : ChatThreadAction

    /** "导出日志"（事件 JSON lines + 应用日志，经系统分享发出，N175）。 */
    public data object ExportLog : ChatThreadAction

    /** "删除"（菜单项，弹确认框）。 */
    public data object DeleteThread : ChatThreadAction

    public data object ConfirmDelete : ChatThreadAction

    public data object DismissDelete : ChatThreadAction

    /** 关闭一次性提示。 */
    public data object DismissNotice : ChatThreadAction

    /** 打开分叉来源对话（ForkMarker 点击，导航类）。 */
    public data class OpenSourceThread(
        val threadId: String,
    ) : ChatThreadAction

    /** 点击步骤行的快捷入口（任务/流程详情、编辑、AI 编辑）。 */
    public data class OpenLink(
        val link: StepLink,
    ) : ChatThreadAction
}

/** ViewModel → 导航的一次性事件（[com.example.autop.feature.chat.ChatNavigator] 执行）。 */
public sealed interface ChatNavEvent {
    /** 把当前对话页替换为 [threadId]（新建对话首发送 / 分叉后）。 */
    public data class ReplaceThread(
        val threadId: String,
    ) : ChatNavEvent

    /** 返回上一级（删除对话后）。 */
    public data object NavigateBack : ChatNavEvent

    /** 发送后让出屏幕（`ui.chat.backgroundOnSend`，[com.example.autop.feature.chat.ChatNavigator.backgroundApp]）。 */
    public data object Background : ChatNavEvent

    /** "导出日志"：导出文本已组装好，由 app 写缓存文件并拉起系统分享（N175）。 */
    public data class ExportLog(
        val text: String,
    ) : ChatNavEvent

    /** 步骤行的快捷入口：由 app 按 [StepLink.kind] 跳任务/流程页面。 */
    public data class OpenLink(
        val link: StepLink,
    ) : ChatNavEvent

    /** "保存为任务"：跳 AI 创建任务页，[hint] 为带入的会话摘录。 */
    public data class OpenTaskCreate(
        val hint: String,
    ) : ChatNavEvent
}
