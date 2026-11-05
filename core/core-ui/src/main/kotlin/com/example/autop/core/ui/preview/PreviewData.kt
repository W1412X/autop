/**
 * 预览假数据（docs/tech/08 §7）：所有 `@Preview` 统一取这里的样本，
 * 保证预览图互相对照。仅供预览引用，不进入运行时代码。
 */
package com.example.autop.core.ui.preview

/** 预览样本。 */
public object PreviewData {
    public const val TITLE: String = "示例标题"
    public const val SUBTITLE: String = "这是一行副标题说明文字"
    public const val BODY: String = "这是一段示例正文，用于预览排版与颜色。"
    public const val VALUE: String = "跟随系统"
    public const val BUTTON: String = "按钮"
    public const val CHIP: String = "选项"
    public const val STATUS: String = "运行中"
    public const val INPUT: String = "输入内容…"
    public const val ERROR: String = "加载失败，请检查网络后重试"
    public const val BANNER: String = "这是一条提示信息"
    public const val SUBTITLE_JOINED: String = "模型 · 参数 · 时间"
}
