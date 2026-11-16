/**
 * 计划卡预览（docs/tech/08 §7）：亮色、暗色各一例，覆盖四种状态与一条超长文本。
 * 预览函数保持 internal：detekt 的 UnusedPrivateMember 会误报私有预览。
 */
package com.example.autop.core.thread.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.autop.agent.PlanItem
import com.example.autop.agent.PlanStatus
import com.example.autop.core.ui.preview.PreviewTheme

private const val PREVIEW_EXPLANATION: String = "先打开微信找到张三的会话，再发送消息。"

private val PREVIEW_PLAN: List<PlanItem> =
    listOf(
        PlanItem(step = "打开微信", status = PlanStatus.DONE),
        PlanItem(step = "找到张三的聊天", status = PlanStatus.IN_PROGRESS),
        PlanItem(step = "输入并发送消息", status = PlanStatus.PENDING),
        PlanItem(step = "在搜索结果里点开张三的会话，确认顶部标题是张三的备注名后再继续输入消息内容", status = PlanStatus.PENDING),
        PlanItem(step = "手动通知李四", status = PlanStatus.DROPPED),
    )

@Preview(name = "plan light")
@Composable
internal fun PlanCardLightPreview() =
    PreviewTheme {
        PlanCardView(items = PREVIEW_PLAN, explanation = PREVIEW_EXPLANATION)
    }

@Preview(name = "plan dark")
@Composable
internal fun PlanCardDarkPreview() =
    PreviewTheme(dark = true) {
        PlanCardView(items = PREVIEW_PLAN, explanation = PREVIEW_EXPLANATION)
    }
