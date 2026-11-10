/**
 * 设备层错误码（docs/tech/05 §1.4）。`retryable` 语义见
 * [com.example.autop.core.common.AppError.retryable]。
 */
package com.example.autop.device

/** 错误码常量（字符串照抄 docs/tech/05 §1.4）。 */
public object DeviceErrorCodes {
    /** 无障碍服务未连接。 */
    public const val SERVICE_UNAVAILABLE: String = "device.service_unavailable"

    /** 应用窗口节点树为空（应用可能屏蔽无障碍节点；a11y 观测不可用，可自动降级视觉）。 */
    public const val APP_TREE_EMPTY: String = "device.app_tree_empty"

    /** 点击后屏幕指纹未变化（点击可能没有生效；结果附提示，不算失败）。 */
    public const val NO_EFFECT: String = "device.no_effect"

    /** 参数缺失/非法（如 tap 未给 id 或 x,y）。 */
    public const val INVALID_ARGUMENT: String = "device.invalid_argument"

    /** 定位器找不到节点。 */
    public const val NODE_NOT_FOUND: String = "device.node_not_found"

    /** 定位器命中多个节点且无法消歧。 */
    public const val NODE_AMBIGUOUS: String = "device.node_ambiguous"

    /** 节点动作或手势执行失败。 */
    public const val ACTION_FAILED: String = "device.action_failed"

    /** 手势被系统取消。 */
    public const val GESTURE_CANCELLED: String = "device.gesture_cancelled"

    /** 截图失败。 */
    public const val SCREENSHOT_FAILED: String = "device.screenshot_failed"

    /** 安全窗口禁止截图（策略引擎视为敏感信号）。 */
    public const val SCREENSHOT_SECURE: String = "device.screenshot_secure"

    /** 截图频率受限且重试耗尽。 */
    public const val SCREENSHOT_RATE_LIMITED: String = "device.screenshot_rate_limited"

    /** 找不到应用。 */
    public const val APP_NOT_FOUND: String = "device.app_not_found"

    /** 启动应用失败。 */
    public const val LAUNCH_FAILED: String = "device.launch_failed"

    /** 系统拒绝打开（SecurityException / 后台启动拦截等，WP7.5）。 */
    public const val LAUNCH_DENIED: String = "device.launch_denied"

    /** 点位上没有可拾取的元素。 */
    public const val ELEMENT_NOT_FOUND: String = "device.element_not_found"

    /** 没有发现可关闭的弹窗。 */
    public const val POPUP_NOT_FOUND: String = "device.popup_not_found"

    /** 无法强制结束应用（shell 通道不可用或包名被拒绝）。 */
    public const val RESTART_UNAVAILABLE: String = "device.restart_unavailable"

    /** 强制结束应用失败（包名不合法或被保护）。 */
    public const val RESTART_DENIED: String = "device.restart_denied"

    /** 打开 URI 失败。 */
    public const val URI_FAILED: String = "device.uri_failed"

    /** 目标节点不可编辑。 */
    public const val NOT_EDITABLE: String = "device.not_editable"

    /** 目标节点不可滚动。 */
    public const val NOT_SCROLLABLE: String = "device.not_scrollable"

    /** 观测模式不可用（未启用/服务未连接/模型未就绪）。 */
    public const val MODE_UNAVAILABLE: String = "device.mode_unavailable"

    /** llm 观测解析失败（重试耗尽）。 */
    public const val LLM_PARSE_FAILED: String = "device.llm_parse_failed"

    /** 视觉解析失败（超时或解析器异常）。 */
    public const val VISUAL_PARSE_FAILED: String = "device.visual_parse_failed"

    /** 所有观测方式都失败。 */
    public const val NO_OBSERVATION: String = "device.no_observation"

    /** 设备已锁屏。 */
    public const val LOCKED: String = "device.locked"

    /** 等待条件超时。 */
    public const val TIMEOUT: String = "device.timeout"

    /** 更新检测失败（网络不可达、GitHub 限流或响应异常）。 */
    public const val UPDATE_CHECK_FAILED: String = "device.update_check_failed"

    /** 更新下载失败（网络中断、写入失败或资产缺失）。 */
    public const val UPDATE_DOWNLOAD_FAILED: String = "device.update_download_failed"

    /** 未授权"安装未知应用"（已跳授权页，授权后重试）。 */
    public const val INSTALL_PERMISSION_REQUIRED: String = "device.install_permission_required"

    /** 拉起系统安装器失败。 */
    public const val INSTALL_FAILED: String = "device.install_failed"
}
