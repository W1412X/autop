/**
 * 弹窗规则加载（docs/design/flows-recording-recovery.md R6）：从 `assets/rules/popup_rules.json` 读取，
 * 进程内缓存一份。解析失败或 schemaVersion 不符时抛错，避免静默用错规则。
 */
package com.example.autop.device.impl.popup

import android.content.Context
import com.example.autop.core.common.AutopJson
import com.example.autop.device.core.popup.CURRENT_POPUP_RULES_SCHEMA_VERSION
import com.example.autop.device.core.popup.PopupRules
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 弹窗规则加载器。 */
@Singleton
public class PopupRulesLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val rules: PopupRules by lazy { load() }

        /** 规则对象（惰性加载一次）。 */
        public fun get(): PopupRules = rules

        private fun load(): PopupRules {
            val json =
                context.assets
                    .open(RULES_PATH)
                    .reader(Charsets.UTF_8)
                    .readText()
            val parsed = AutopJson.default.decodeFromString(PopupRules.serializer(), json)
            check(parsed.schemaVersion == CURRENT_POPUP_RULES_SCHEMA_VERSION) {
                "popup_rules.json schemaVersion must be $CURRENT_POPUP_RULES_SCHEMA_VERSION, " +
                    "got ${parsed.schemaVersion}"
            }
            return parsed
        }

        private companion object {
            const val RULES_PATH = "rules/popup_rules.json"
        }
    }
