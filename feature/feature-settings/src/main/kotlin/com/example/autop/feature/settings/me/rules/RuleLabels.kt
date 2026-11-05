/**
 * 规则类型的显示名与选项清单（docs/tech/06 §6 规则类型表、§6 类别表）。
 */
package com.example.autop.feature.settings.me.rules

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.autop.feature.settings.R

/** 内置规则类型（docs/tech/06 §6）。 */
internal val RULE_TYPES: List<String> =
    listOf("password_field", "text_any", "package", "activity_regex", "secure_with_text", "uri_scheme")

/** 策略类别（docs/tech/06 §6 `policy_rules.json` 的 categories）。 */
internal val RULE_CATEGORIES: List<String> =
    listOf("payment", "password", "biometric", "transfer", "system_security", "account_danger", "install")

/** 作用范围（docs/tech/06 §5 `RuleScope`）。 */
internal val RULE_SCOPES: List<String> = listOf("TARGET", "SCREEN")

/** 规则类型显示名。 */
@Composable
internal fun ruleTypeLabel(type: String): String =
    when (type) {
        "password_field" -> stringResource(R.string.rule_type_password_field)
        "text_any" -> stringResource(R.string.rule_type_text_any)
        "package" -> stringResource(R.string.rule_type_package)
        "activity_regex" -> stringResource(R.string.rule_type_activity_regex)
        "secure_with_text" -> stringResource(R.string.rule_type_secure_with_text)
        "uri_scheme" -> stringResource(R.string.rule_type_uri_scheme)
        else -> type
    }

/** 策略类别显示名（与 `policy_rules.json` 的 reasonKey 文案一致）。 */
@Composable
internal fun ruleCategoryLabel(category: String): String =
    when (category) {
        "payment" -> stringResource(R.string.policy_reason_payment)
        "password" -> stringResource(R.string.policy_reason_password)
        "biometric" -> stringResource(R.string.policy_reason_biometric)
        "transfer" -> stringResource(R.string.policy_reason_transfer)
        "system_security" -> stringResource(R.string.policy_reason_system_security)
        "account_danger" -> stringResource(R.string.policy_reason_account_danger)
        "install" -> stringResource(R.string.policy_reason_install)
        else -> category
    }

/** 作用范围显示名。 */
@Composable
internal fun ruleScopeLabel(scope: String): String =
    when (scope) {
        "TARGET" -> stringResource(R.string.rule_scope_target)
        "SCREEN" -> stringResource(R.string.rule_scope_screen)
        else -> scope
    }

/** 校验错误文案。 */
@Composable
internal fun rulesErrorText(error: RulesError): String =
    when (error) {
        RulesError.ID_INVALID -> stringResource(R.string.rules_error_id)
        RulesError.PARAMS_EMPTY -> stringResource(R.string.rules_error_params)
        RulesError.MIN_HITS_INVALID -> stringResource(R.string.rules_error_min_hits)
        RulesError.SAVE_FAILED -> stringResource(R.string.rules_error_save_failed)
    }
