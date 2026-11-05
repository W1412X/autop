/**
 * 对话页状态（docs/tech/08 §4.2，docs/standards/coding-standards §8：
 * 不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.core.config.UiChatSection

/** 等待回答的问题（docs/tech/08 §4.2 AskCard）。 */
public data class WaitingQuestion(
    val question: String,
    val options: List<String>,
)

/** 一次性提示种类（文案在 strings，ViewModel 不接触资源）。 */
public enum class ThreadNotice {
    /** "保存为任务"未落地（feature-tasks 在后续 WP）。 */
    SAVE_AS_TASK,

    /** "导出日志"未落地。 */
    EXPORT_LOG,

    /** "已复制"（元信息行复制图标，N183）。 */
    COPIED,
}

/** 对话页弹层状态。 */
public data class ChatThreadSheet(
    /** 顶栏"更多"菜单显隐。 */
    val moreMenu: Boolean = false,
    /** 长按条目菜单的目标 seq（null = 无）。 */
    val menuSeq: Long? = null,
    /** "查看原始数据"全屏页的目标 seq（null = 无）。 */
    val rawSeq: Long? = null,
    /** 删除对话确认框显隐。 */
    val deleteConfirm: Boolean = false,
)

/** 对话页状态。 */
public data class ChatThreadUiState(
    /** 线程 id（`chat/thread/{threadId}`；"new" = 未建线程的新对话）。 */
    val threadId: String = "new",
    val title: String? = null,
    /** 归并后的时间线。 */
    val entries: List<TimelineEntry> = emptyList(),
    /** 本页是否有活跃 Run（输入框切到"补充指令…"）。 */
    val running: Boolean = false,
    /** 无障碍已连接（false 时输入禁用并显示提示，docs/tech/08 §4.1）。 */
    val accessibilityConnected: Boolean = true,
    val input: String = "",
    /** "附带当前屏幕"开关（发送时 `attachCurrentScreen`）。 */
    val attachScreen: Boolean = true,
    /** 输入框最大行数（`ui.chat.inputMaxLines`）。 */
    val inputMaxLines: Int = UiChatSection().inputMaxLines,
    val waitingQuestion: WaitingQuestion? = null,
    val notice: ThreadNotice? = null,
    val sheet: ChatThreadSheet = ChatThreadSheet(),
    /** `sheet.rawSeq` 对应事件的原始 JSON（mono 展示、可复制）。 */
    val rawJson: String? = null,
)
