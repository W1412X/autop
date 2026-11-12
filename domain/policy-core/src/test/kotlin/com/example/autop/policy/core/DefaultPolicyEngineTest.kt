package com.example.autop.policy.core

import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Logger
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyOverrides
import com.example.autop.policy.PolicySubject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** [DefaultPolicyEngine] 求值语义（docs/tech/06 §7 策略部分）。 */
class DefaultPolicyEngineTest {
    @Test
    fun `password field rule blocks when target is password`() =
        runTest {
            val engine = engine(listOf(PWD_TARGET))
            val decision = engine.evaluate(subject().copy(targetIsPassword = true), PolicyOverrides())
            assertThat(decision).isEqualTo(PolicyDecision.Block("pwd.target", "password", "policy_reason_password"))
        }

    @Test
    fun `password field rule allows plain target`() =
        runTest {
            val engine = engine(listOf(PWD_TARGET))
            val decision = engine.evaluate(subject().copy(targetIsPassword = false), PolicyOverrides())
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `password field rule blocks screen with password field`() =
        runTest {
            val engine = engine(listOf(PWD_SCREEN))
            val decision = engine.evaluate(subject().copy(hasPasswordField = true), PolicyOverrides())
            assertThat(decision).isEqualTo(PolicyDecision.Block("pwd.screen", "password", "policy_reason_password"))
        }

    @Test
    fun `text any rule matches target keyword`() =
        runTest {
            val engine = engine(listOf(PAY_TARGET))
            val decision = engine.evaluate(subject().copy(targetText = "确认付款 ¥100"), PolicyOverrides())
            assertThat(decision).isEqualTo(PolicyDecision.Block("pay.target", "payment", "policy_reason_payment"))
        }

    @Test
    fun `text any rule min hits requires multiple keywords`() =
        runTest {
            val engine = engine(listOf(PAY_MIN_HITS))
            val oneHit = engine.evaluate(subject().copy(targetText = "收银台"), PolicyOverrides())
            assertThat(oneHit).isEqualTo(PolicyDecision.Allow)
            val twoHits = engine.evaluate(subject().copy(targetText = "收银台 输入支付密码"), PolicyOverrides())
            assertThat(twoHits).isEqualTo(PolicyDecision.Block("pay.hits", "payment", "policy_reason_payment"))
        }

    @Test
    fun `text any rule with packages limited to those packages`() =
        runTest {
            val engine = engine(listOf(SYS_SETTINGS))
            val settingsScreen = subject().copy(screenTexts = listOf("无障碍"))
            val inSettings =
                engine.evaluate(settingsScreen.copy(packageName = "com.android.settings"), PolicyOverrides())
            assertThat(inSettings).isEqualTo(
                PolicyDecision.Block("sys.settings", "system_security", "policy_reason_system_security"),
            )
            val other = engine.evaluate(settingsScreen.copy(packageName = "com.other.app"), PolicyOverrides())
            assertThat(other).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `package rule matches equal and prefix`() =
        runTest {
            val engine = engine(listOf(PKG_RULE))
            val equal = engine.evaluate(subject().copy(packageName = "com.bank.app"), PolicyOverrides())
            assertThat(equal).isEqualTo(PolicyDecision.Block("pkg.rule", "payment", "policy_reason_payment"))
            val prefix = engine.evaluate(subject().copy(packageName = "com.bank.pay.activity"), PolicyOverrides())
            assertThat(prefix).isEqualTo(PolicyDecision.Block("pkg.rule", "payment", "policy_reason_payment"))
            val other = engine.evaluate(subject().copy(packageName = "com.other.app"), PolicyOverrides())
            assertThat(other).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `activity regex rule matches and survives invalid pattern`() =
        runTest {
            val engine = engine(listOf(ACTIVITY_RULE, BAD_REGEX_RULE))
            val hit = engine.evaluate(subject().copy(activity = "com.app.CheckoutActivity"), PolicyOverrides())
            assertThat(hit).isEqualTo(PolicyDecision.Block("act.rule", "payment", "policy_reason_payment"))
            val miss = engine.evaluate(subject().copy(activity = "com.app.MainActivity"), PolicyOverrides())
            assertThat(miss).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `secure with text rule needs secure window and keyword`() =
        runTest {
            val engine = engine(listOf(SECURE_RULE))
            val screen = subject().copy(windowSecure = true, screenTexts = listOf("请输入密码"))
            val hit = engine.evaluate(screen, PolicyOverrides())
            assertThat(hit).isEqualTo(PolicyDecision.Block("sec.rule", "payment", "policy_reason_payment"))
            val notSecure = engine.evaluate(screen.copy(windowSecure = false), PolicyOverrides())
            assertThat(notSecure).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `uri scheme rule matches extra uri scheme`() =
        runTest {
            val engine = engine(listOf(URI_RULE))
            val hit = engine.evaluate(subject().copy(extra = mapOf("uri" to "alipay://pay?x=1")), PolicyOverrides())
            assertThat(hit).isEqualTo(PolicyDecision.Block("uri.rule", "payment", "policy_reason_payment"))
            val miss =
                engine.evaluate(subject().copy(extra = mapOf("uri" to "https://pay.example.com")), PolicyOverrides())
            assertThat(miss).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `dangerous uri schemes are blocked case-insensitively`() =
        runTest {
            val engine = engine(listOf(DANGEROUS_SCHEME_RULE))
            val blocked =
                engine.evaluate(
                    subject().copy(extra = mapOf("uri" to "javascript:alert(1)")),
                    PolicyOverrides(),
                )
            assertThat(
                blocked,
            ).isEqualTo(PolicyDecision.Block("uri.dangerous", "system_security", "policy_reason_system_security"))
            val upperScheme = engine.evaluate(subject().copy(extra = mapOf("uri" to "Intent:#x")), PolicyOverrides())
            assertThat(
                upperScheme,
            ).isEqualTo(PolicyDecision.Block("uri.dangerous", "system_security", "policy_reason_system_security"))
            val allowed =
                engine.evaluate(
                    subject().copy(extra = mapOf("uri" to "https://example.com")),
                    PolicyOverrides(),
                )
            assertThat(allowed).isEqualTo(PolicyDecision.Allow)
            val upperAllowed =
                engine.evaluate(
                    subject().copy(extra = mapOf("uri" to "HTTPS://example.com")),
                    PolicyOverrides(),
                )
            assertThat(upperAllowed).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `non overridable category ignores task overrides`() =
        runTest {
            val engine = engine(listOf(PAY_TARGET))
            val decision =
                engine.evaluate(
                    subject().copy(targetText = "确认付款"),
                    PolicyOverrides(allowCategories = setOf("payment")),
                )
            assertThat(decision).isEqualTo(PolicyDecision.Block("pay.target", "payment", "policy_reason_payment"))
        }

    @Test
    fun `overridable category is allowed by task overrides`() =
        runTest {
            val engine = engine(listOf(SYS_SETTINGS))
            val settingsSubject = subject().copy(packageName = "com.android.settings", screenTexts = listOf("无障碍"))
            val blocked = engine.evaluate(settingsSubject, PolicyOverrides())
            assertThat(blocked).isInstanceOf(PolicyDecision.Block::class.java)
            val allowed =
                engine.evaluate(
                    settingsSubject,
                    PolicyOverrides(allowCategories = setOf("system_security")),
                )
            assertThat(allowed).isEqualTo(PolicyDecision.Allow)
        }

    @Test
    fun `user rules merge with builtin rules`() =
        runTest {
            val engine = engine(listOf(PWD_TARGET), userRules = listOf(USER_RULE))
            val decision = engine.evaluate(subject().copy(targetText = "导出全部数据"), PolicyOverrides())
            assertThat(decision).isEqualTo(
                PolicyDecision.Block("user.rule", "account_danger", "policy_reason_account_danger"),
            )
        }

    @Test
    fun `unknown rule type is skipped with warning`() =
        runTest {
            val logger = RecordingLogger()
            val engine = engine(listOf(UNKNOWN_TYPE_RULE), logger = logger)
            val decision = engine.evaluate(subject().copy(targetIsPassword = true), PolicyOverrides())
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
            assertThat(logger.warnEvents).contains("policy_rule_unknown_type")
        }

    @Test
    fun `disabled rule is skipped silently`() =
        runTest {
            val logger = RecordingLogger()
            val engine = engine(listOf(DISABLED_RULE), logger = logger)
            val decision = engine.evaluate(subject().copy(targetIsPassword = true), PolicyOverrides())
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
            assertThat(logger.warnEvents).isEmpty()
        }

    @Test
    fun `tools field limits rule to listed tools`() =
        runTest {
            val engine = engine(listOf(PAY_TAP_ONLY))
            val swipe = engine.evaluate(subject().copy(toolId = "tool.swipe", targetText = "确认付款"), PolicyOverrides())
            assertThat(swipe).isEqualTo(PolicyDecision.Allow)
            val tap = engine.evaluate(subject().copy(toolId = "tool.tap", targetText = "确认付款"), PolicyOverrides())
            assertThat(tap).isEqualTo(PolicyDecision.Block("pay.tap", "payment", "policy_reason_payment"))
        }

    @Test
    fun `unknown category is treated as non overridable`() =
        runTest {
            val engine = engine(listOf(MYSTERY_RULE))
            val decision =
                engine.evaluate(
                    subject().copy(targetText = "神秘按钮"),
                    PolicyOverrides(allowCategories = setOf("mystery")),
                )
            assertThat(decision).isEqualTo(PolicyDecision.Block("mystery.rule", "mystery", "policy_reason_mystery"))
        }

    @Test
    fun `redaction follows blocked packages`() {
        val engine = engine(emptyList(), blockedPackages = listOf("com.secret.app"))
        assertThat(engine.redaction("com.secret.app")).isTrue()
        assertThat(engine.redaction("com.other.app")).isFalse()
        assertThat(engine.redaction(null)).isFalse()
    }

    @Test
    fun `blocked target package is intercepted without any rule`() =
        runTest {
            val engine = engine(emptyList(), blockedPackages = listOf("com.blocked.app"))
            val decision =
                engine.evaluate(
                    subject().copy(extra = mapOf("targetPackage" to "com.blocked.app")),
                    PolicyOverrides(),
                )
            assertThat(decision)
                .isEqualTo(PolicyDecision.Block("builtin.blocked_package", "屏蔽的应用", "policy_reason_blocked_app"))
        }

    @Test
    fun `blocked foreground package intercepts in-app actions`() =
        runTest {
            val engine = engine(emptyList(), blockedPackages = listOf("com.blocked.app"))
            val decision = engine.evaluate(subject().copy(packageName = "com.blocked.app"), PolicyOverrides())
            assertThat(decision)
                .isEqualTo(PolicyDecision.Block("builtin.blocked_package", "屏蔽的应用", "policy_reason_blocked_app"))
        }

    @Test
    fun `unblocked subject falls through to allow`() =
        runTest {
            val engine = engine(emptyList(), blockedPackages = listOf("com.blocked.app"))
            val decision =
                engine.evaluate(
                    subject().copy(packageName = "com.other.app", extra = mapOf("targetPackage" to "com.other.app")),
                    PolicyOverrides(),
                )
            assertThat(decision).isEqualTo(PolicyDecision.Allow)
        }

    private fun engine(
        builtinRules: List<String>,
        userRules: List<String> = emptyList(),
        blockedPackages: List<String> = emptyList(),
        logger: Logger = NoopLogger,
    ): TestEngine {
        val doc =
            buildJsonObject {
                put("categories", obj(CATEGORIES))
                put("rules", buildJsonArray { builtinRules.forEach { add(obj(it)) } })
            }
        val overrides =
            buildJsonObject {
                put(
                    "policy",
                    buildJsonObject {
                        put("builtinRules", doc)
                        put("userRules", buildJsonArray { userRules.forEach { add(obj(it)) } })
                        put("blockedPackages", buildJsonArray { blockedPackages.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        val provider = FakeConfigProvider(overrides)
        return TestEngine(DefaultPolicyEngine(BuiltinRuleTypes.ALL.toSet(), logger), provider.current())
    }

    private fun subject(): PolicySubject = PolicySubject(toolId = "tool.tap")

    @Test
    fun `rules come from passed snapshot not global config`() =
        runTest {
            val engine = engine(listOf(PAY_TARGET))
            val decision =
                engine.engine.evaluate(
                    subject().copy(targetText = "确认付款 ¥100"),
                    PolicyOverrides(),
                    engine.config,
                )
            assertThat(decision).isEqualTo(PolicyDecision.Block("pay.target", "payment", "policy_reason_payment"))
            val empty = engine(emptyList())
            val ignored =
                engine.engine.evaluate(
                    subject().copy(targetText = "确认付款 ¥100"),
                    PolicyOverrides(),
                    empty.config,
                )
            assertThat(ignored).isEqualTo(PolicyDecision.Allow)
        }

    /** 测试替身：evaluate/redaction 默认用构造时的配置快照（WP7.2 起规则随参数传入）。 */
    private class TestEngine(
        val engine: DefaultPolicyEngine,
        val config: com.example.autop.core.config.ConfigSnapshot,
    ) {
        suspend fun evaluate(
            subject: PolicySubject,
            overrides: PolicyOverrides,
        ): PolicyDecision = engine.evaluate(subject, overrides, config)

        fun redaction(packageName: String?): Boolean = engine.redaction(packageName, config)
    }

    private fun obj(json: String): JsonObject = AutopJson.default.parseToJsonElement(json) as JsonObject
}

private const val CATEGORIES = """
    {
      "payment": {"overridable": false, "reasonKey": "policy_reason_payment"},
      "password": {"overridable": false, "reasonKey": "policy_reason_password"},
      "account_danger": {"overridable": true, "reasonKey": "policy_reason_account_danger"},
      "system_security": {"overridable": true, "reasonKey": "policy_reason_system_security"}
    }
"""

private const val PWD_TARGET = """
    {"id": "pwd.target", "type": "password_field", "category": "password", "scope": "TARGET", "params": {}}
"""
private const val PWD_SCREEN = """
    {"id": "pwd.screen", "type": "password_field", "category": "password", "scope": "SCREEN", "params": {}}
"""
private const val PAY_TARGET = """
    {"id": "pay.target", "type": "text_any", "category": "payment", "scope": "TARGET",
     "params": {"keywords": ["确认付款", "立即支付"]}}
"""
private const val PAY_MIN_HITS = """
    {"id": "pay.hits", "type": "text_any", "category": "payment", "scope": "TARGET",
     "params": {"keywords": ["收银台", "输入支付密码"], "minHits": 2}}
"""
private const val SYS_SETTINGS = """
    {"id": "sys.settings", "type": "text_any", "category": "system_security", "scope": "SCREEN",
     "params": {"keywords": ["无障碍"], "packages": ["com.android.settings"]}}
"""
private const val PKG_RULE = """
    {"id": "pkg.rule", "type": "package", "category": "payment", "scope": "SCREEN",
     "params": {"packages": ["com.bank.app"], "prefixes": ["com.bank."]}}
"""
private const val ACTIVITY_RULE = """
    {"id": "act.rule", "type": "activity_regex", "category": "payment", "scope": "SCREEN",
     "params": {"patterns": ["(?i).*(cashier|checkout).*"]}}
"""
private const val BAD_REGEX_RULE = """
    {"id": "act.bad", "type": "activity_regex", "category": "payment", "scope": "SCREEN",
     "params": {"patterns": ["(("]}}
"""
private const val SECURE_RULE = """
    {"id": "sec.rule", "type": "secure_with_text", "category": "payment", "scope": "SCREEN",
     "params": {"keywords": ["密码"]}}
"""
private const val URI_RULE = """
    {"id": "uri.rule", "type": "uri_scheme", "category": "payment", "scope": "SCREEN",
     "params": {"schemes": ["alipay"]}}
"""
private const val DANGEROUS_SCHEME_RULE = """
    {"id": "uri.dangerous", "type": "uri_scheme", "category": "system_security", "scope": "SCREEN",
     "params": {"schemes": ["javascript", "file", "content", "intent"]}}
"""
private const val USER_RULE = """
    {"id": "user.rule", "type": "text_any", "category": "account_danger", "scope": "TARGET",
     "params": {"keywords": ["导出全部数据"]}}
"""
private const val UNKNOWN_TYPE_RULE = """
    {"id": "unknown.rule", "type": "no_such_type", "category": "payment", "scope": "TARGET",
     "params": {"keywords": ["确认付款"]}}
"""
private const val DISABLED_RULE = """
    {"id": "disabled.rule", "type": "password_field", "category": "password", "scope": "TARGET",
     "params": {}, "enabled": false}
"""
private const val PAY_TAP_ONLY = """
    {"id": "pay.tap", "type": "text_any", "category": "payment", "scope": "TARGET",
     "tools": ["tool.tap"], "params": {"keywords": ["确认付款"]}}
"""
private const val MYSTERY_RULE = """
    {"id": "mystery.rule", "type": "text_any", "category": "mystery", "scope": "TARGET",
     "params": {"keywords": ["神秘按钮"]}}
"""
