/**
 * feature-chat 的导航回调（docs/tech/08 §3：feature 暴露
 * `NavGraphBuilder.xxxGraph(navigator: XxxNavigator)`，feature 之间不互相引用）。
 * 由 app 侧实现本接口完成跳转。
 */
package com.example.autop.feature.chat

import com.example.autop.feature.chat.thread.StepLink

/** 对话模块导航回调接口。 */
public interface ChatNavigator {
    /** 打开对话页 [threadId]（`chat/thread/{threadId}`，由 app 实现）。 */
    public fun openThread(threadId: String)

    /** 新建对话首次发送后，把"新对话"页替换为真实线程页（由 app 实现）。 */
    public fun replaceThread(threadId: String)

    /** 跳系统无障碍设置（docs/tech/08 §4.1 AccessibilityNotice 的"去开启"，由 app 实现）。 */
    public fun openAccessibilitySettings()

    /** 发送后让出屏幕（docs/tech/08 §4.2 InputArea `ui.chat.backgroundOnSend`，由 app 实现 `moveTaskToBack`）。 */
    public fun backgroundApp()

    /** "导出日志"（N175）：把导出文本写入缓存文件并拉起系统分享（由 app 实现，FileProvider）。 */
    public fun shareExport(text: String)

    /** 步骤行快捷入口：按 [StepLink.kind] 跳任务/流程详情、编辑或 AI 编辑（由 app 映射路由）。 */
    public fun openStepLink(link: StepLink)

    /** "保存为任务"：跳 AI 创建任务页，[hint] 为带入的会话摘录（由 app 映射路由）。 */
    public fun openTaskCreate(hint: String)

    /** 返回上一级（`popBackStack`，由 app 实现）。 */
    public fun navigateBack()
}
