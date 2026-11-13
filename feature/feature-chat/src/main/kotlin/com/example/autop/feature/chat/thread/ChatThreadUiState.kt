/**
 * 对话页状态（docs/tech/08 §4.2，docs/standards/coding-standards §8：
 * 不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.chat.thread

import com.example.autop.core.config.UiChatSection
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.task.ImageAttachment

/** 等待回答的问题（docs/tech/08 §4.2 AskCard）。 */
public data class WaitingQuestion(
    val question: String,
    val options: List<String>,
)

/** 一次性提示种类（文案在 strings，ViewModel 不接触资源）。 */
public enum class ThreadNotice {
    /** "导出日志"未落地。 */
    EXPORT_LOG,

    /** "已复制"（元信息行复制图标，N183）。 */
    COPIED,

    /** 选中的图片读取失败（格式不支持或文件不可读）。 */
    IMAGE_FAILED,

    /** 补充指令（steer）失败：目标 Run 已结束或不存在，输入框保留原文（WP8.5）。 */
    STEER_FAILED,
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
    /** 会话级模型覆盖（null = 跟随默认）与可选模型。 */
    val modelProfileId: String? = null,
    val modelOptions: List<OptionItem> = emptyList(),
    /** 全局默认模型 id（输入区芯片跟随默认时显示其名称与"默认"标记）。 */
    val defaultModelId: String? = null,
    /** 待发送的图片（已压缩，发送后清空）。 */
    val images: List<ImageAttachment> = emptyList(),
    /** 单条消息可附图片数上限（`ui.chat.attachMaxImages`）。 */
    val maxImages: Int = UiChatSection().attachMaxImages,
    /** 输入框最大行数（`ui.chat.inputMaxLines`）。 */
    val inputMaxLines: Int = UiChatSection().inputMaxLines,
    val waitingQuestion: WaitingQuestion? = null,
    val notice: ThreadNotice? = null,
    val sheet: ChatThreadSheet = ChatThreadSheet(),
    /** `sheet.rawSeq` 对应事件的原始 JSON（mono 展示、可复制）。 */
    val rawJson: String? = null,
) {
    /** 本会话是否为分叉分支：路径上有分叉标记（分支自身的 ThreadCreated 总在路径末段）。 */
    val isBranch: Boolean get() = entries.any { it.item is TimelineItem.ForkMarker }
}
