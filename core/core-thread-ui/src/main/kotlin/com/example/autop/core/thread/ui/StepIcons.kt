/**
 * 步骤行图标（docs/tech/08 §4.2 StepRow，N178）：工具类别图标识别"这是什么动作"，
 * 状态由行尾状态图标 + 语义色区分（状态不只靠颜色，docs/tech/08 §6）。
 */
package com.example.autop.core.thread.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.IconSize
import com.example.autop.core.ui.theme.StatusTone
import com.example.autop.core.ui.theme.scaled

/**
 * 工具步骤的类别图标（统一组件：对话页步骤行与悬浮进度面板共用）。
 * 只有图标本身，不加底色或容器，颜色由调用方按状态传入。
 */
@Composable
public fun StepToolIcon(
    toolId: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = toolIconOf(toolId),
        contentDescription = null,
        modifier = modifier.size(IconSize.Sm.scaled()),
        tint = tint,
    )
}

/** 工具类别图标（[stepLabelOf] 归类后查表；未知工具用问号图标）。 */
internal fun toolIconOf(toolId: String): ImageVector = TOOL_ICONS[stepLabelOf(toolId)] ?: AutopIcons.HelpOutline

/** 工具类别 → 图标表（查表免 when 长分支，detekt 圈复杂度阈值 15）。 */
private val TOOL_ICONS: Map<StepLabel, ImageVector> =
    mapOf(
        StepLabel.TAP to AutopIcons.TouchApp,
        StepLabel.TAP_NODE to AutopIcons.AdsClick,
        StepLabel.INPUT_TEXT to AutopIcons.Keyboard,
        StepLabel.SCROLL to AutopIcons.SwipeVertical,
        StepLabel.SCROLL_NODE to AutopIcons.SwapVert,
        StepLabel.SWIPE to AutopIcons.Swipe,
        StepLabel.GLOBAL_ACTION to AutopIcons.Gesture,
        StepLabel.OPEN_APP to AutopIcons.Launch,
        StepLabel.RESTART_APP to AutopIcons.RestartAlt,
        StepLabel.INTENT to AutopIcons.OpenInNew,
        StepLabel.SCREENSHOT to AutopIcons.Screenshot,
        StepLabel.OBSERVE to AutopIcons.Visibility,
        StepLabel.WAIT to AutopIcons.HourglassEmpty,
        StepLabel.LIST_APPS to AutopIcons.Apps,
        StepLabel.LIST_TASKS to AutopIcons.ListAlt,
        StepLabel.CREATE_TASK to AutopIcons.AddTask,
        StepLabel.UPDATE_TASK to AutopIcons.Edit,
        StepLabel.NOTIFY_USER to AutopIcons.Campaign,
        StepLabel.READ_NOTIFICATIONS to AutopIcons.NotificationsActive,
        StepLabel.CLIPBOARD_GET to AutopIcons.ContentPaste,
        StepLabel.CLIPBOARD_SET to AutopIcons.ContentPasteGo,
        StepLabel.RUN_SHELL to AutopIcons.Terminal,
        StepLabel.DEVICE_STATE to AutopIcons.PermDeviceInformation,
        StepLabel.ASK_USER to AutopIcons.QuestionAnswer,
        StepLabel.UPDATE_PLAN to AutopIcons.Checklist,
        StepLabel.FINISH to AutopIcons.TaskAlt,
        StepLabel.NOTE to AutopIcons.EditNote,
        StepLabel.HISTORY_RECALL to AutopIcons.History,
        StepLabel.RUN_FLOW to AutopIcons.PlayCircle,
        StepLabel.LIST_FLOWS to AutopIcons.AccountTree,
        StepLabel.CREATE_FLOW to AutopIcons.AddBox,
        StepLabel.EDIT_FLOW to AutopIcons.DesignServices,
        StepLabel.UPDATE_FLOW to AutopIcons.Update,
        StepLabel.SAVE_AS_FLOW to AutopIcons.SaveAs,
        StepLabel.PATCH_FLOW_NODE to AutopIcons.Construction,
        StepLabel.MEMORY_LIST to AutopIcons.Storage,
        StepLabel.MEMORY_READ to AutopIcons.MenuBook,
        StepLabel.MEMORY_SEARCH to AutopIcons.SavedSearch,
        StepLabel.MEMORY_WRITE to AutopIcons.PostAdd,
    )

/** 状态图标（行尾小标：运行中 / 成功 / 失败）。 */
internal fun stateIconOf(state: StepState): ImageVector =
    when (state) {
        StepState.RUNNING -> AutopIcons.Play
        StepState.DONE -> AutopIcons.Check
        StepState.FAILED -> AutopIcons.Error
    }

/** 状态语义色（图标与底色共用）。 */
internal fun stepTone(state: StepState): StatusTone =
    when (state) {
        StepState.RUNNING -> StatusTone.RUNNING
        StepState.DONE -> StatusTone.SUCCEEDED
        StepState.FAILED -> StatusTone.BLOCKED
    }
