/**
 * 字体 token（docs/tech/08 §1.2）：取值同 LimeTV `LimeTypography`，
 * 外加工具参数/屏幕树原文/JSON 展示用的等宽样式与全局缩放。
 */
package com.example.autop.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 全局字体入口。 */
public object AutopTypography {
    /** 基准排版（缩放前）。 */
    public val base: Typography =
        Typography(
            displayLarge =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 57.sp,
                    lineHeight = 64.sp,
                ),
            displayMedium =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 45.sp,
                    lineHeight = 52.sp,
                ),
            displaySmall =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 36.sp,
                    lineHeight = 44.sp,
                ),
            headlineLarge =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                ),
            headlineMedium =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                ),
            headlineSmall =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                ),
            titleLarge =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                ),
            titleMedium =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    letterSpacing = 0.15.sp,
                ),
            titleSmall =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.1.sp,
                ),
            bodyLarge =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    letterSpacing = 0.5.sp,
                ),
            bodyMedium =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.25.sp,
                ),
            bodySmall =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 0.4.sp,
                ),
            labelLarge =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.1.sp,
                ),
            labelMedium =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 0.5.sp,
                ),
            labelSmall =
                TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 0.5.sp,
                ),
        )

    /** 等宽样式：工具参数、屏幕树原文、JSON。 */
    public val mono: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
}

/** 全局缩放：字号与行高同乘 [factor]（外观设置 0.9 / 1.0 / 1.15 / 1.3）。 */
public fun Typography.scaled(factor: Float): Typography =
    copy(
        displayLarge = displayLarge.scaledStyle(factor),
        displayMedium = displayMedium.scaledStyle(factor),
        displaySmall = displaySmall.scaledStyle(factor),
        headlineLarge = headlineLarge.scaledStyle(factor),
        headlineMedium = headlineMedium.scaledStyle(factor),
        headlineSmall = headlineSmall.scaledStyle(factor),
        titleLarge = titleLarge.scaledStyle(factor),
        titleMedium = titleMedium.scaledStyle(factor),
        titleSmall = titleSmall.scaledStyle(factor),
        bodyLarge = bodyLarge.scaledStyle(factor),
        bodyMedium = bodyMedium.scaledStyle(factor),
        bodySmall = bodySmall.scaledStyle(factor),
        labelLarge = labelLarge.scaledStyle(factor),
        labelMedium = labelMedium.scaledStyle(factor),
        labelSmall = labelSmall.scaledStyle(factor),
    )

private fun TextStyle.scaledStyle(factor: Float): TextStyle =
    copy(
        fontSize = fontSize * factor,
        lineHeight = lineHeight * factor,
    )
