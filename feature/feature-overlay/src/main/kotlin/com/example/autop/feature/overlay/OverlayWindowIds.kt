package com.example.autop.feature.overlay

/** 悬浮窗窗口 id（`OverlayHost.register/show/update/hide` 的键，app 组装时注册）。 */
public object OverlayWindowIds {
    /** 悬浮球窗口（常驻；docs/tech/08 §5.1）。 */
    public const val BALL: String = "overlay.ball"

    /** 迷你面板窗口（圆弧菜单「即时创建」展开，focusable 供输入法；docs/tech/08 §5.2）。 */
    public const val PANEL: String = "overlay.panel"

    /** 圆弧菜单窗口（点球展开的整屏透明层；docs/tech/08 §5.1，N182）。 */
    public const val MENU: String = "overlay.menu"

    /** 任务进度悬浮窗（chat 同款消息流 + 提问卡；docs/tech/08 §5.3，N182）。 */
    public const val PROGRESS: String = "overlay.progress"

    /** 工具测试悬浮面板（选工具→任意页面执行→看输出；N198）。 */
    public const val PROBE: String = "overlay.probe"
}
