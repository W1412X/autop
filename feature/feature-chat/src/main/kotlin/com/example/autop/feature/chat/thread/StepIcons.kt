/**
 * 步骤行图标（docs/tech/08 §4.2 StepRow，N178）：工具类别图标识别"这是什么动作"，
 * 状态由行尾状态图标 + 语义色区分（状态不只靠颜色，docs/tech/08 §6）。
 */
package com.example.autop.feature.chat.thread

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.autop.core.ui.component.AutopIcons
import com.example.autop.core.ui.theme.StatusTone

/** 工具类别图标（[stepLabelOf] 归类后查表；未知工具用问号图标）。 */
internal fun toolIconOf(toolId: String): ImageVector = TOOL_ICONS[stepLabelOf(toolId)] ?: AutopIcons.HelpOutline

/** 工具类别 → 图标表（查表免 when 长分支，detekt 圈复杂度阈值 15）。 */
private val TOOL_ICONS: Map<StepLabel, ImageVector> =
    mapOf(
        StepLabel.TAP to AutopIcons.TouchApp,
        StepLabel.INPUT_TEXT to AutopIcons.Keyboard,
        StepLabel.SCROLL to AutopIcons.Swipe,
        StepLabel.SWIPE to AutopIcons.Swipe,
        StepLabel.GLOBAL_ACTION to AutopIcons.OpenWith,
        StepLabel.OPEN_APP to AutopIcons.Launch,
        StepLabel.OPEN_URI to AutopIcons.Link,
        StepLabel.SCREENSHOT to AutopIcons.PhotoCamera,
        StepLabel.OBSERVE to AutopIcons.Visibility,
        StepLabel.WAIT to AutopIcons.Schedule,
        StepLabel.LIST_APPS to AutopIcons.ListAlt,
        StepLabel.DEVICE_STATE to AutopIcons.PhoneAndroid,
        StepLabel.CLIPBOARD_GET to AutopIcons.ContentPaste,
        StepLabel.CLIPBOARD_SET to AutopIcons.ContentPaste,
        StepLabel.LIST_TASKS to AutopIcons.ListAlt,
        StepLabel.CREATE_TASK to AutopIcons.Add,
        StepLabel.UPDATE_TASK to AutopIcons.Edit,
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
