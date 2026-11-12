/**
 * 导航规则加载（intent-tool §5.1）：从 `assets/rules/navigation_apps.json` 读取，进程内缓存一份。
 * 解析失败、schemaVersion 不符或加载校验不通过时抛错，避免静默用错规则（与 RestartRulesLoader 一致）。
 */
package com.example.autop.device.impl.intent

import android.content.Context
import com.example.autop.core.common.AutopJson
import com.example.autop.device.core.navigation.CURRENT_NAVIGATION_RULES_SCHEMA_VERSION
import com.example.autop.device.core.navigation.NavigationRules
import com.example.autop.device.core.navigation.NavigationRulesValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 导航规则加载器。 */
@Singleton
public class NavigationAppsRulesLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val rules: NavigationRules by lazy { load() }

        /** 规则对象（惰性加载一次）。 */
        public fun get(): NavigationRules = rules

        private fun load(): NavigationRules {
            val json =
                context.assets
                    .open(RULES_PATH)
                    .reader(Charsets.UTF_8)
                    .readText()
            val parsed = AutopJson.default.decodeFromString(NavigationRules.serializer(), json)
            check(parsed.schemaVersion == CURRENT_NAVIGATION_RULES_SCHEMA_VERSION) {
                "navigation_apps.json schemaVersion must be $CURRENT_NAVIGATION_RULES_SCHEMA_VERSION, " +
                    "got ${parsed.schemaVersion}"
            }
            val errors = NavigationRulesValidator.validate(parsed)
            check(errors.isEmpty()) { "navigation_apps.json is invalid: ${errors.joinToString("; ")}" }
            return parsed
        }

        private companion object {
            const val RULES_PATH = "rules/navigation_apps.json"
        }
    }
