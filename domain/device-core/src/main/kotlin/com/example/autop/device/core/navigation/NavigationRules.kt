/**
 * 导航应用规则表的数据模型（intent-tool §3.1、§6.1）。内容来自 `assets/rules/navigation_apps.json`。
 * 包名、URI 模板、出行方式映射都是数据，代码里不出现具体应用的字面量。
 */
package com.example.autop.device.core.navigation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 当前 `navigation_apps.json` 的 schemaVersion。 */
public const val CURRENT_NAVIGATION_RULES_SCHEMA_VERSION: Int = 1

/** 导航规则表（顶层带 schemaVersion）。 */
@Serializable
public data class NavigationRules(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("apps") val apps: List<NavigationAppRule>,
)

/** 一个导航应用的规则。[priority] 越小越优先。 */
@Serializable
public data class NavigationAppRule(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String,
    @SerialName("packageName") val packageName: String,
    @SerialName("priority") val priority: Int,
    @SerialName("uri") val uri: NavigationUriRule,
)

/** URI 模板：[base] 后拼接按 [params] 生成的查询参数；[modes] 把出行方式映射为该应用的取值。 */
@Serializable
public data class NavigationUriRule(
    @SerialName("base") val base: String,
    @SerialName("params") val params: List<NavigationParamRule>,
    @SerialName("modes") val modes: Map<String, String>,
)

/**
 * 一个查询参数。[value] 可含占位符 `{name}` `{lat}` `{lng}` `{mode}` `{source}`。
 * `when` 决定参数是否输出（`always` 等），[onlyModes] 限定只在某些出行方式下输出。
 */
@Serializable
public data class NavigationParamRule(
    @SerialName("key") val key: String,
    @SerialName("value") val value: String,
    @SerialName("when") val `when`: String = WHEN_ALWAYS,
    @SerialName("onlyModes") val onlyModes: List<String>? = null,
)

/** 参数输出条件：无条件。 */
public const val WHEN_ALWAYS: String = "always"

/** 参数输出条件：有目的地名称。 */
public const val WHEN_HAS_NAME: String = "has_name"

/** 参数输出条件：有坐标。 */
public const val WHEN_HAS_COORD: String = "has_coord"

/** 参数输出条件：有名称且无坐标。 */
public const val WHEN_NAME_ONLY: String = "name_only"

/** 参数输出条件：有坐标且无名称。 */
public const val WHEN_COORD_ONLY: String = "coord_only"

/** 参数输出条件：同时有名称与坐标。 */
public const val WHEN_COORD_AND_NAME: String = "coord_and_name"
