package com.example.autop.core.common

/**
 * 平台能力开关（docs/tech/02 §4.1）。定义在 core-common（api 层也要引用），
 * 查询实现在 core-platform。
 *
 * `Build.VERSION.SDK_INT` 只允许出现在 `PlatformCapabilitiesImpl` 与 device-impl 中。
 */
public enum class Capability {
    /** SDK ≥ 30：无障碍 `takeScreenshot`。 */
    ACCESSIBILITY_SCREENSHOT,

    /** SDK ≥ 30：输入法动作按钮（`ImeAction`）。 */
    IME_ENTER_ACTION,

    /** SDK ≥ 33：通知运行时权限 `POST_NOTIFICATIONS`。 */
    NOTIFICATION_RUNTIME_PERMISSION,

    /** SDK 31–32：精确闹钟需要用户授权。 */
    EXACT_ALARM_NEEDS_GRANT,

    /** SDK ≥ 34：全屏 Intent 需要用户在设置中授予。 */
    FULL_SCREEN_INTENT_NEEDS_GRANT,

    /** SDK ≥ 31：`Notification.Action.setAuthenticationRequired`。 */
    NOTIFICATION_AUTH_REQUIRED_ACTION,

    /** SDK ≥ 33：侧载应用开无障碍需要“允许受限设置”。 */
    RESTRICTED_SETTINGS,

    /** SDK ≥ 34：`accessibilityDataSensitive` 视图（真机验证项 Q5）。 */
    ACCESSIBILITY_DATA_SENSITIVE,

    /** SDK ≥ 31：动态取色（Monet，docs/tech/08 §1.1）。 */
    DYNAMIC_COLOR,
}

/** 平台能力查询端口。实现不得抛异常，一律返回布尔值。 */
public interface PlatformCapabilities {
    public fun has(c: Capability): Boolean
}
