/**
 * 默认策略引擎（docs/tech/06 §6）：内置规则 + 用户规则按顺序求值，
 * 第一条命中即决定；不可覆盖类别不受 Task 覆盖影响。
 */
package com.example.autop.policy.core

import com.example.autop.core.common.Logger
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.PolicyConfig
import com.example.autop.core.config.PolicySection
import com.example.autop.policy.PolicyDecision
import com.example.autop.policy.PolicyEngine
import com.example.autop.policy.PolicyOverrides
import com.example.autop.policy.PolicyRule
import com.example.autop.policy.PolicyRuleType
import com.example.autop.policy.PolicySubject
import javax.inject.Inject
import javax.inject.Singleton

/** [PolicyEngine] 默认实现。 */
@Singleton
public class DefaultPolicyEngine
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        ruleTypes: Set<@JvmSuppressWildcards PolicyRuleType>,
        private val logger: Logger,
    ) : PolicyEngine {
        private val typesById: Map<String, PolicyRuleType> = ruleTypes.associateBy { it.type }

        private val cacheLock = Any()
        private var cachedSource: PolicySection? = null
        private var cachedRules: PolicyRuleSet? = null

        override suspend fun evaluate(
            subject: PolicySubject,
            overrides: PolicyOverrides,
        ): PolicyDecision {
            blockedDecision(subject)?.let { return it }
            val rules = rulesFor(configProvider.current()[PolicyConfig.POLICY])
            return rules.rules.firstNotNullOfOrNull { decisionFor(it, subject, overrides, rules.categories) }
                ?: PolicyDecision.Allow
        }

        override fun redaction(packageName: String?): Boolean =
            packageName != null && packageName in configProvider.current()[PolicyConfig.POLICY].blockedPackages

        /**
         * 屏蔽应用（`policy.blockedPackages`）：打开目标（工具自报 `targetPackage`）或
         * 前台命中一律拦截并暂停——此前该名单只参与观测脱敏（[redaction]）、不进求值，
         * 设置形同虚设（2026-11-09 修复）。
         */
        private fun blockedDecision(subject: PolicySubject): PolicyDecision? {
            val blocked = configProvider.current()[PolicyConfig.POLICY].blockedPackages
            val hit = subject.extra[EXTRA_TARGET_PACKAGE] in blocked || subject.packageName in blocked
            return if (hit) {
                PolicyDecision.Block(BLOCKED_RULE_ID, BLOCKED_CATEGORY, BLOCKED_REASON_KEY)
            } else {
                null
            }
        }

        /** 单条规则的决定：命中且未被覆盖 → Block；否则返回 null 继续下一条。 */
        private fun decisionFor(
            rule: PolicyRule,
            subject: PolicySubject,
            overrides: PolicyOverrides,
            categories: Map<String, PolicyCategory>,
        ): PolicyDecision? {
            val matcher = typesById[rule.type]
            val hit = matcher != null && applies(rule, subject.toolId) && matcher.matches(rule, subject)
            val category = categories[rule.category] ?: unknownCategory(rule)
            val overridden = category.overridable && rule.category in overrides.allowCategories
            return if (hit && !overridden) PolicyDecision.Block(rule.id, rule.category, category.reasonKey) else null
        }

        /** 规则适用性：`tools` 为空表示所有 WRITE_SCREEN 工具。 */
        private fun applies(
            rule: PolicyRule,
            toolId: String,
        ): Boolean = rule.tools.isEmpty() || toolId in rule.tools

        /** 类别未登记时按不可覆盖处理，原因键回退为 `policy_reason_<类别>`。 */
        private fun unknownCategory(rule: PolicyRule): PolicyCategory =
            PolicyCategory(overridable = false, reasonKey = "policy_reason_${rule.category}")

        /** 解析结果按配置段实例缓存：配置变化（新快照）后自动重解析。 */
        private fun rulesFor(section: PolicySection): PolicyRuleSet {
            synchronized(cacheLock) {
                val cached = cachedRules
                if (cached == null || section !== cachedSource) {
                    cachedRules =
                        PolicyRuleParser.parse(
                            builtin = section.builtinRules,
                            userRules = section.userRules,
                            knownTypes = typesById.keys,
                            logger = logger,
                        )
                    cachedSource = section
                }
            }
            return checkNotNull(cachedRules)
        }

        private companion object {
            /** 工具自报的目标包键（open_app/open_uri 的 extra）。 */
            const val EXTRA_TARGET_PACKAGE: String = "targetPackage"

            /** 屏蔽应用内置拦截：规则标识与文案键；category 直排进拦截提示模板。 */
            const val BLOCKED_RULE_ID: String = "builtin.blocked_package"
            const val BLOCKED_CATEGORY: String = "屏蔽的应用"
            const val BLOCKED_REASON_KEY: String = "policy_reason_blocked_app"
        }
    }
