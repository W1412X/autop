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

    /** 流程录制面板（flows-recording-recovery.md V5）。 */
    public const val RECORD: String = "overlay.record"

    /** 流程录制的整屏拾取层（点击元素，V5 拾取模式）。 */
    public const val PICKER: String = "overlay.picker"

    /** 执行期状态指示：屏幕边缘光（ADR-0014）；全屏、不可触摸、不可聚焦。 */
    public const val INDICATOR: String = "overlay.indicator"

    /** 运行控制条（原顶部胶囊升级）：顶部窄条，可触摸（操作信息 + 暂停/继续/中止按钮）。 */
    public const val RUN_BAR: String = "overlay.run_bar"

    /** 运行详情面板（点控制条操作信息展开）：条下消息流观测视图，可触摸（滚动）。 */
    public const val RUN_DETAIL: String = "overlay.run_detail"

    /** 配对卡（无线调试配对码输入小窗，发现可配对设备即弹；可聚焦供输入法）。 */
    public const val PAIRING: String = "overlay.pairing"
}
