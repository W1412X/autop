/**
 * 外部文本的不可信信封（架构规范 §10，tool_optimize 02 §14）：通知、剪贴板等来自应用外部的文字统一包装，
 * 格式与 flow-core `ExprFunctions.untrusted` 相同，只是 source 不同。
 */
package com.example.autop.device.core

/** 不可信文本包装。 */
public object UntrustedText {
    private const val CLOSE = "</untrusted>"

    /** 通知文本来源。 */
    public const val SOURCE_NOTIFICATION: String = "notification"

    /** 剪贴板文本来源。 */
    public const val SOURCE_CLIPBOARD: String = "clipboard"

    /** 用 `<untrusted source="[source]">…</untrusted>` 包装；去掉正文里的闭合标签，防止提前闭合。 */
    public fun wrap(
        source: String,
        text: String,
    ): String = "<untrusted source=\"$source\">${text.replace(CLOSE, "")}$CLOSE"
}
