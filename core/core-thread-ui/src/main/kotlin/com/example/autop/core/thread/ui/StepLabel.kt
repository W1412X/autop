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
    TAP_NODE(R.string.step_tap_node),
    INPUT_TEXT(R.string.step_input_text),
    SCROLL(R.string.step_scroll),
    SCROLL_NODE(R.string.step_scroll_node),
    SWIPE(R.string.step_swipe),
    GLOBAL_ACTION(R.string.step_global_action),
    OPEN_APP(R.string.step_open_app),
    RESTART_APP(R.string.step_restart_app),
    INTENT(R.string.step_intent),
    SCREENSHOT(R.string.step_screenshot),
    OBSERVE(R.string.step_observe),
    WAIT(R.string.step_wait),
    LIST_APPS(R.string.step_list_apps),
    LIST_TASKS(R.string.step_list_tasks),
    CREATE_TASK(R.string.step_create_task),
    UPDATE_TASK(R.string.step_update_task),
    NOTIFY_USER(R.string.step_notify_user),
    READ_NOTIFICATIONS(R.string.step_read_notifications),
    CLIPBOARD_GET(R.string.step_clipboard_get),
    CLIPBOARD_SET(R.string.step_clipboard_set),
    RUN_SHELL(R.string.step_run_shell),
    DEVICE_STATE(R.string.step_device_state),
    ASK_USER(R.string.step_ask_user),
    UPDATE_PLAN(R.string.step_update_plan),
    FINISH(R.string.step_finish),
    NOTE(R.string.step_note),
    HISTORY_RECALL(R.string.step_history_recall),
    RUN_FLOW(R.string.step_run_flow),
    LIST_FLOWS(R.string.step_list_flows),
    CREATE_FLOW(R.string.step_create_flow),
    EDIT_FLOW(R.string.step_edit_flow),
    UPDATE_FLOW(R.string.step_update_flow),
    SAVE_AS_FLOW(R.string.step_save_as_flow),
    PATCH_FLOW_NODE(R.string.step_patch_flow_node),
    MEMORY_LIST(R.string.step_memory_list),
    MEMORY_READ(R.string.step_memory_read),
    MEMORY_SEARCH(R.string.step_memory_search),
    MEMORY_WRITE(R.string.step_memory_write),

    /** 未知工具（模板取 `step_other`，参数为工具 id）。 */
    OTHER(R.string.step_other),
}
