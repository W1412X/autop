package com.example.autop.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 悬浮球固定配色（`ui.appearance.ballColor` 为 BLACK / WHITE 时使用，ADR-0045）。
 * 不跟随主题色；眼睛与符号在表情内部取反色，保证对比。
 */
public object PetColors {
    /** 固定黑：近黑略带暖灰，避免在深色界面上完全融没。 */
    public val Black: Color = Color(0xFF17171A)

    /** 固定白：近白，避免纯白在浅色界面上刺眼。 */
    public val White: Color = Color(0xFFFAFAFA)
}
