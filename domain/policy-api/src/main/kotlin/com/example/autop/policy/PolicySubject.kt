/**
 * 策略评估的主体（docs/tech/06 §5）：由 device-impl 的 `PolicySubjectBuilder` 生成，
 * 描述“一次写屏动作发生在什么屏幕上、目标是什么”。
 */
package com.example.autop.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 策略主体（规则匹配的输入）。 */
@Serializable
public data class PolicySubject(
    @SerialName("toolId") val toolId: String,
    @SerialName("packageName") val packageName: String? = null,
    @SerialName("activity") val activity: String? = null,
    @SerialName("targetText") val targetText: String? = null,
    @SerialName("targetIsPassword") val targetIsPassword: Boolean = false,
    @SerialName("targetDataSensitive") val targetDataSensitive: Boolean = false,
    @SerialName("screenTexts") val screenTexts: List<String> = emptyList(),
    @SerialName("hasPasswordField") val hasPasswordField: Boolean = false,
    @SerialName("windowSecure") val windowSecure: Boolean = false,
    /** 工具自报的附加信息：`uri`、`targetPackage`、`inputLength` 等。 */
    @SerialName("extra") val extra: Map<String, String> = emptyMap(),
)
