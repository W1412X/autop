/**
 * 步骤描述标签（docs/tech/08 §4.2 StepRow）：[verbRes] 对应 strings 的 `step_*`
 * 动词模板，参数拼接模板（`step_desc_*`）由 StepDescriber 的调用方按标签选择。
 */
package com.example.autop.core.thread.ui

import com.example.autop.core.thread.ui.R

/**
 * 步骤描述标签。
 *
 * @property verbRes 描述动词资源（无参数占位时单独使用）。
 */
public enum class StepLabel(
    public val verbRes: Int,
) {
    TAP(R.string.step_tap),
    INPUT_TEXT(R.string.step_input_text),
    SCROLL(R.string.step_scroll),
    SWIPE(R.string.step_swipe),
    GLOBAL_ACTION(R.string.step_global_action),
    OPEN_APP(R.string.step_open_app),
    INTENT(R.string.step_intent),
    SCREENSHOT(R.string.step_screenshot),
    OBSERVE(R.string.step_observe),
    WAIT(R.string.step_wait),
    LIST_APPS(R.string.step_list_apps),
    DEVICE_STATE(R.string.step_device_state),
    CLIPBOARD_GET(R.string.step_clipboard_get),
    CLIPBOARD_SET(R.string.step_clipboard_set),
    LIST_TASKS(R.string.step_list_tasks),
    CREATE_TASK(R.string.step_create_task),
    UPDATE_TASK(R.string.step_update_task),

    /** 未知工具（模板取 `step_other`，参数为工具 id）。 */
    OTHER(R.string.step_other),
}
