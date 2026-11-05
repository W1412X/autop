/**
 * 内置规则类型（docs/tech/06 §6 的类型表）：`password_field`、`text_any`、`package`、
 * `activity_regex`、`secure_with_text`、`uri_scheme`。新增规则类型 = 实现
 * `PolicyRuleType` 并登记到多绑定集合。
 */
package com.example.autop.policy.core

import com.example.autop.policy.PolicyRule
import com.example.autop.policy.PolicyRuleType
import com.example.autop.policy.PolicySubject
import com.example.autop.policy.RuleScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 内置规则类型集合。 */
public object BuiltinRuleTypes {
    /** 全部内置类型（顺序即匹配实现的展示顺序）。 */
    public val ALL: List<PolicyRuleType> =
        listOf(PasswordField, TextAny, PackageRule, ActivityRegex, SecureWithText, UriScheme)
}

/** 密码框：TARGET 看 `targetIsPassword`，SCREEN 看 `hasPasswordField`。 */
private object PasswordField : PolicyRuleType {
    override val type: String = "password_field"

    override fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean =
        when (rule.scope) {
            RuleScope.TARGET -> subject.targetIsPassword
            RuleScope.SCREEN -> subject.hasPasswordField
        }
}

/** 任意关键词命中：TARGET 看 `targetText`，SCREEN 看 `screenTexts`；每个出现的关键词计 1 次。 */
private object TextAny : PolicyRuleType {
    override val type: String = "text_any"

    override fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean {
        val packages = rule.strings("packages")
        if (packages.isNotEmpty() && subject.packageName !in packages) return false
        val texts =
            when (rule.scope) {
                RuleScope.TARGET -> listOfNotNull(subject.targetText)
                RuleScope.SCREEN -> subject.screenTexts
            }
        val joined = texts.joinToString("\n")
        val hits = rule.strings("keywords").count { joined.contains(it) }
        return hits >= rule.intParam("minHits", DEFAULT_MIN_HITS)
    }
}

/** 包名相等或前缀匹配。 */
private object PackageRule : PolicyRuleType {
    override val type: String = "package"

    override fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean {
        val pkg = subject.packageName ?: return false
        return rule.strings("packages").contains(pkg) || rule.strings("prefixes").any { pkg.startsWith(it) }
    }
}

/** activity 匹配任一正则。 */
private object ActivityRegex : PolicyRuleType {
    override val type: String = "activity_regex"

    override fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean {
        val activity = subject.activity ?: return false
        return rule.strings("patterns").any { matchesSafely(it, activity) }
    }

    /** 非法正则视为不命中（用户规则不该让引擎崩溃）。 */
    private fun matchesSafely(
        pattern: String,
        activity: String,
    ): Boolean = runCatching { Regex(pattern).containsMatchIn(activity) }.getOrDefault(false)
}

/** 安全窗口 + 关键词。 */
private object SecureWithText : PolicyRuleType {
    override val type: String = "secure_with_text"

    override fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean {
        if (!subject.windowSecure) return false
        val joined = subject.screenTexts.joinToString("\n")
        return rule.strings("keywords").any { joined.contains(it) }
    }
}

/** URI scheme 在列表中。 */
private object UriScheme : PolicyRuleType {
    override val type: String = "uri_scheme"

    override fun matches(
        rule: PolicyRule,
        subject: PolicySubject,
    ): Boolean {
        val uri = subject.extra["uri"] ?: return false
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        return rule.strings("schemes").any { it.lowercase() == scheme }
    }
}

/** 规则参数里的字符串数组；缺失或类型不符时为空。 */
private fun PolicyRule.strings(name: String): List<String> =
    (params[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()

/** 规则参数里的整数；缺失或类型不符时用 [fallback]。 */
private fun PolicyRule.intParam(
    name: String,
    fallback: Int,
): Int = (params[name] as? JsonPrimitive)?.intOrNull ?: fallback

private const val DEFAULT_MIN_HITS = 1
