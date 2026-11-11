/**
 * 工具 id → 图标/短名映射（docs/tech/08 §5.3，N182）：进度悬浮窗工具步骤行用，
 * 图标表与对话页 StepIcons 同款（N178）。
 */
package com.example.autop.feature.overlay.progress

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.autop.core.ui.component.AutopIcons

/** 工具 id → 类别图标（同对话页 StepIcons 表，N178）。 */
internal fun toolIconOf(toolId: String): ImageVector = TOOL_ICONS[toolId] ?: AutopIcons.HelpOutline

/** 工具 id → 短名（去 `tool.` 前缀，工具 id 是数据标识不做翻译）。 */
internal fun toolNameOf(toolId: String): String = toolId.removePrefix("tool.")

private val TOOL_ICONS: Map<String, ImageVector> =
    mapOf(
        "tool.tap" to AutopIcons.TouchApp,
        "tool.tap_node" to AutopIcons.TouchApp,
        "tool.input_text" to AutopIcons.Keyboard,
        "tool.scroll" to AutopIcons.Swipe,
        "tool.scroll_node" to AutopIcons.Swipe,
        "tool.swipe" to AutopIcons.Swipe,
        "tool.global_action" to AutopIcons.OpenWith,
        "tool.open_app" to AutopIcons.Launch,
        "tool.open_uri" to AutopIcons.Link,
        "tool.screenshot" to AutopIcons.PhotoCamera,
        "tool.observe" to AutopIcons.Visibility,
        "tool.wait" to AutopIcons.Schedule,
        "tool.list_apps" to AutopIcons.ListAlt,
        "tool.get_device_state" to AutopIcons.PhoneAndroid,
        "tool.clipboard_get" to AutopIcons.ContentPaste,
        "tool.clipboard_set" to AutopIcons.ContentPaste,
    )
