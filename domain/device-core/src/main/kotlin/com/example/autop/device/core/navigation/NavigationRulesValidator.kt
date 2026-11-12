/**
 * 导航规则表的加载校验（intent-tool §3.1）。纯函数，返回错误描述列表；空列表表示通过。
 * 加载器对非空结果直接抛错，不静默回退（与 PopupRulesLoader 一致）。
 */
package com.example.autop.device.core.navigation

import com.example.autop.device.core.NavMode

/** 规则表校验。 */
public object NavigationRulesValidator {
    /** 校验整份规则表，返回全部错误。 */
    public fun validate(rules: NavigationRules): List<String> =
        buildList {
            if (rules.schemaVersion != CURRENT_NAVIGATION_RULES_SCHEMA_VERSION) {
                add("schemaVersion must be $CURRENT_NAVIGATION_RULES_SCHEMA_VERSION, got ${rules.schemaVersion}")
            }
            if (rules.apps.isEmpty()) add("apps must not be empty")
            duplicatesOf(rules.apps.map { it.id })
                .takeIf { it.isNotEmpty() }
                ?.let { add("duplicate app id: $it") }
            duplicatesOf(rules.apps.map { it.priority })
                .takeIf { it.isNotEmpty() }
                ?.let { add("duplicate priority: $it") }
            rules.apps.forEach { addAll(validateApp(it)) }
        }

    private fun validateApp(app: NavigationAppRule): List<String> =
        buildList {
            if (app.id.isBlank()) add("app id must not be blank")
            if (app.packageName.isBlank()) add("${app.id}: packageName must not be blank")
            if (app.uri.base.isBlank()) add("${app.id}: uri.base must not be blank")
            val missingModes = NavMode.entries.map { it.id }.filter { it !in app.uri.modes }
            if (missingModes.isNotEmpty()) add("${app.id}: uri.modes misses $missingModes")
            placeholderErrors(app.id, app.uri.base).forEach { add(it) }
            app.uri.params.forEach { addAll(validateParam(app.id, it)) }
        }

    private fun validateParam(
        appId: String,
        param: NavigationParamRule,
    ): List<String> =
        buildList {
            if (param.key.isBlank()) add("$appId: param key must not be blank")
            if (param.`when` !in WHEN_VALUES) add("$appId: unknown when '${param.`when`}'")
            param.onlyModes
                ?.filter { it !in NavMode.entries.map { mode -> mode.id } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { add("$appId: unknown onlyModes $it") }
            placeholderErrors(appId, param.value).forEach { add(it) }
        }

    /** 模板里出现的未知占位符。 */
    private fun placeholderErrors(
        appId: String,
        template: String,
    ): List<String> =
        PLACEHOLDER
            .findAll(template)
            .map { it.groupValues[1] }
            .filter { it !in PLACEHOLDERS }
            .map { "$appId: unknown placeholder {$it} in '$template'" }
            .toList()

    private fun <T> duplicatesOf(values: List<T>): List<T> =
        values
            .groupBy {
                it
            }.filterValues { it.size > 1 }
            .keys
            .toList()

    private val PLACEHOLDER = Regex("\\{([^{}]*)\\}")
    private val PLACEHOLDERS = setOf("name", "lat", "lng", "mode", "source")
    private val WHEN_VALUES =
        setOf(
            WHEN_ALWAYS,
            WHEN_HAS_NAME,
            WHEN_HAS_COORD,
            WHEN_NAME_ONLY,
            WHEN_COORD_ONLY,
            WHEN_COORD_AND_NAME,
        )
}
