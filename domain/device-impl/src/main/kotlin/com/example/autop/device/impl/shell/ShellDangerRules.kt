package com.example.autop.device.impl.shell

import android.content.Context
import com.example.autop.core.common.AutopJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** 当前 `shell_danger_rules.json` 的 schemaVersion。 */
public const val CURRENT_SHELL_DANGER_RULES_SCHEMA_VERSION: Int = 1

/** 规则模式中替换为本应用包名的占位符。 */
private const val SELF_PLACEHOLDER = "{self}"

/** 命中的危险规则：规则 id 与类别的字符串资源键（[labelKey]，由工具解析为本地化文案）。 */
public data class DangerMatch(
    val ruleId: String,
    val labelKey: String,
)

/** `shell_danger_rules.json` 的文件结构。 */
@Serializable
internal data class ShellDangerRuleFile(
    val schemaVersion: Int,
    val rules: List<ShellDangerRuleEntry>,
)

@Serializable
internal data class ShellDangerRuleEntry(
    val id: String,
    val labelKey: String,
    val patterns: List<String>,
)

/**
 * 危险操作规则（shell-exec 设计 §7）：只决定执行前是否需要确认，不做命令审查、不直接拒绝命令。
 *
 * 匹配：大小写不敏感，空白折叠为单个空格。模式两端带空格时按整词匹配（如 ` dd ` 不误伤 `add`），
 * 其余为子串匹配（如 `rm -r` 同时命中 `rm -rf`）。`{self}` 替换为本应用包名。
 * 这是轻量匹配，可以被改写绕过；绕过的结果只是跳过确认。
 */
public class ShellDangerRules internal constructor(
    private val rules: List<ShellDangerRuleEntry>,
) {
    /** 第一个命中的规则；未命中返回 null。 */
    public fun match(
        command: String,
        selfPackage: String,
    ): DangerMatch? {
        val haystack = " ${squash(command)} "
        for (rule in rules) {
            val hit =
                rule.patterns.any { pattern ->
                    val needle = squash(pattern.replace(SELF_PLACEHOLDER, selfPackage))
                    needle.isNotBlank() && haystack.contains(needle)
                }
            if (hit) return DangerMatch(rule.id, rule.labelKey)
        }
        return null
    }

    private fun squash(text: String): String = text.lowercase().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** 解析并校验规则文件。schemaVersion 不符或内容不合法时抛 [IllegalStateException]。 */
public fun parseShellDangerRules(json: String): ShellDangerRules {
    val parsed = AutopJson.default.decodeFromString(ShellDangerRuleFile.serializer(), json)
    check(parsed.schemaVersion == CURRENT_SHELL_DANGER_RULES_SCHEMA_VERSION) {
        "shell_danger_rules.json schemaVersion must be $CURRENT_SHELL_DANGER_RULES_SCHEMA_VERSION, " +
            "got ${parsed.schemaVersion}"
    }
    val problems = validateRules(parsed.rules)
    check(problems.isEmpty()) { "shell_danger_rules.json is invalid: ${problems.joinToString("; ")}" }
    return ShellDangerRules(parsed.rules)
}

private fun validateRules(rules: List<ShellDangerRuleEntry>): List<String> =
    buildList {
        if (rules.isEmpty()) add("rules must not be empty")
        rules.forEach { rule ->
            if (rule.id.isBlank()) add("rule id must not be blank")
            if (rule.labelKey.isBlank()) add("rule ${rule.id} labelKey must not be blank")
            if (rule.patterns.isEmpty() || rule.patterns.any { it.isBlank() }) {
                add("rule ${rule.id} patterns must be non-blank")
            }
        }
    }

/** 危险规则加载器：从 `assets/rules/shell_danger_rules.json` 读取一次并缓存。 */
@Singleton
public class ShellDangerRulesLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val rules: ShellDangerRules by lazy { load() }

        /** 规则对象（惰性加载）。 */
        public fun get(): ShellDangerRules = rules

        private fun load(): ShellDangerRules {
            val json =
                context.assets
                    .open(RULES_PATH)
                    .reader(Charsets.UTF_8)
                    .use { it.readText() }
            return parseShellDangerRules(json)
        }

        private companion object {
            const val RULES_PATH = "rules/shell_danger_rules.json"
        }
    }
