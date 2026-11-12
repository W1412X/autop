/**
 * 重启规则加载（tool_optimize 02 §13.3）：从 `assets/rules/restart_rules.json` 读取受保护包名与
 * 重启等待时长，进程内缓存一份。解析失败、schemaVersion 不符或校验不通过时抛错，
 * 避免静默用错规则（与 NavigationAppsRulesLoader 一致）。
 */
package com.example.autop.device.impl.restart

import android.content.Context
import com.example.autop.core.common.AutopJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** `restart_rules.json` 的内容（顶层带 schemaVersion）。 */
@Serializable
public data class RestartRules(
    val schemaVersion: Int,
    /** 不允许 force-stop 的受保护包名。 */
    val protectedPackages: List<String> = emptyList(),
    /** force-stop 后、重新打开前的等待（毫秒）。 */
    val settleMs: Long = 0,
)

/** 当前 restart_rules.json 的 schemaVersion。 */
public const val CURRENT_RESTART_RULES_SCHEMA_VERSION: Int = 1

/** 重启规则加载器。 */
@Singleton
public class RestartRulesLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val rules: RestartRules by lazy { load() }

        /** 规则对象（惰性加载一次）。 */
        public fun get(): RestartRules = rules

        private fun load(): RestartRules {
            val json =
                context.assets
                    .open(RULES_PATH)
                    .reader(Charsets.UTF_8)
                    .readText()
            return parseRestartRules(json)
        }

        private companion object {
            const val RULES_PATH = "rules/restart_rules.json"
        }
    }

/** settleMs 的合法范围（0..10 秒）。 */
private val SETTLE_MS_RANGE: LongRange = 0L..10_000L

/** 解析并校验 `restart_rules.json` 内容；schemaVersion 不符或校验不通过时抛 [IllegalStateException]。 */
internal fun parseRestartRules(json: String): RestartRules {
    val parsed = AutopJson.default.decodeFromString(RestartRules.serializer(), json)
    check(parsed.schemaVersion == CURRENT_RESTART_RULES_SCHEMA_VERSION) {
        "restart_rules.json schemaVersion must be $CURRENT_RESTART_RULES_SCHEMA_VERSION, " +
            "got ${parsed.schemaVersion}"
    }
    val errors = validate(parsed)
    check(errors.isEmpty()) { "restart_rules.json is invalid: ${errors.joinToString("; ")}" }
    return parsed
}

private fun validate(rules: RestartRules): List<String> =
    buildList {
        if (rules.settleMs !in SETTLE_MS_RANGE) {
            add("settleMs must be within $SETTLE_MS_RANGE, was ${rules.settleMs}")
        }
        if (rules.protectedPackages.any { it.isBlank() }) {
            add("protectedPackages must not contain blank package names")
        }
    }
