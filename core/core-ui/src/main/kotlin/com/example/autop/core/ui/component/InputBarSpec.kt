/**
 * 输入栏展示参数（docs/tech/08 §2 `AutopInputBar`）：占位文字与最大行数。
 * 行数来自 `ui.chat.inputMaxLines`，由调用方传入。
 */
package com.example.autop.core.ui.component

/** 输入栏展示参数。 */
public data class InputBarSpec(
    val placeholder: String = "",
    val maxLines: Int = 1,
    /** 密码输入（docs/tech/08 §4.5 模型编辑页 API Key）：文字以圆点显示。 */
    val password: Boolean = false,
)
