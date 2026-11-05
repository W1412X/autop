/**
 * 安全规则页 ViewModel（docs/tech/08 §4.5 me/rules）。
 *
 * 读：[ConfigProvider.observe] 的 `policy.builtinRules`（只读内置规则）与 `policy.userRules`；
 * 写：[ConfigEditor.setOverride] 整表覆盖 `policy.userRules`（增删改都重写整个列表）。
 */
package com.example.autop.feature.settings.me.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.PolicyConfig
import com.example.autop.policy.PolicyRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

@HiltViewModel
public class RulesViewModel
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val editing = MutableStateFlow<RuleDraft?>(null)
        private val error = MutableStateFlow<RulesError?>(null)

        public val uiState: StateFlow<RulesUiState> =
            combine(configProvider.observe(), editing, error) { snapshot, draft, problem ->
                val section = snapshot[PolicyConfig.POLICY]
                RulesUiState(
                    builtin = rowsOf(section.builtinRules["rules"] as? JsonArray),
                    user = section.userRules.map(::rowOf),
                    editing = draft,
                    error = problem,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = RulesUiState(),
            )

        public fun onAction(action: RulesAction) {
            when (action) {
                RulesAction.AddRule -> editing.value = RuleDraft()
                is RulesAction.EditRule -> openEdit(action.id)
                is RulesAction.DeleteRule -> deleteRule(action.index)
                is RulesAction.SetField -> editing.value = action.draft
                RulesAction.SaveRule -> save()
                RulesAction.CancelEdit -> closeEditor()
                RulesAction.DismissError -> error.value = null
            }
        }

        private fun openEdit(id: String) {
            val decoded =
                configProvider.current()[PolicyConfig.POLICY].userRules.mapNotNull { obj ->
                    runCatching { AutopJson.default.decodeFromJsonElement(PolicyRule.serializer(), obj) }.getOrNull()
                }
            editing.value = decoded.firstOrNull { it.id == id }?.let(::draftOf)
        }

        private fun deleteRule(index: Int) {
            val rules = configProvider.current()[PolicyConfig.POLICY].userRules
            write(rules.filterIndexed { i, _ -> i != index })
        }

        private fun save() {
            val draft = editing.value ?: return
            val section = configProvider.current()[PolicyConfig.POLICY]
            val problem = draft.validate(knownIdsOf(section.builtinRules, section.userRules))
            if (problem != null) {
                error.value = problem
                return
            }
            val encoded = AutopJson.default.encodeToJsonElement(PolicyRule.serializer(), draft.toRule()).jsonObject
            val next =
                if (draft.originalId == null) {
                    section.userRules + encoded
                } else {
                    section.userRules.map { obj -> if (rowOf(obj).id == draft.originalId) encoded else obj }
                }
            // 写入是异步的：先关表单并清错误，失败时由 write 的 onErr 置 SAVE_FAILED（不被清理覆盖）。
            error.value = null
            editing.value = null
            write(next)
        }

        private fun closeEditor() {
            editing.value = null
            error.value = null
        }

        private fun write(rules: List<JsonObject>) {
            viewModelScope.launch(dispatchers.io) {
                configEditor.setOverride(USER_RULES_PATH, JsonArray(rules)).onErr { err ->
                    logger.e(
                        tag = TAG,
                        event = "user_rules_write_failed",
                        "code" to err.code,
                        error = err.cause,
                    )
                    error.value = RulesError.SAVE_FAILED
                }
            }
        }

        private fun knownIdsOf(
            builtinRules: JsonObject,
            userRules: List<JsonObject>,
        ): Set<String> =
            (rowsOf(builtinRules["rules"] as? JsonArray) + userRules.map(::rowOf))
                .mapNotNull { it.id }
                .toSet()

        private companion object {
            const val TAG = "RulesSettings"
            val USER_RULES_PATH: String = PolicyConfig.POLICY.path + ".userRules"
        }
    }
