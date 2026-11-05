/**
 * 运行预算页 ViewModel（docs/tech/08 §4.5"运行预算"）：
 * 读 [ConfigProvider.observe] 的 `agent.budget` 段（正数=上限，0=不限）；
 * 写经 [ConfigEditor.setOverride]（`agent.budget.<jsonKey>`，时长按分钟换算毫秒），
 * 恢复默认走 [ConfigEditor.clearOverride]（默认 = 不限）。
 */
package com.example.autop.feature.settings.me.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.AgentBudgetSection
import com.example.autop.core.config.AgentConfig
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@HiltViewModel
public class BudgetViewModel
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val editor = MutableStateFlow<BudgetEditState?>(null)

        public val uiState: StateFlow<BudgetUiState> =
            combine(configProvider.observe(), editor) { snapshot, editing ->
                val budget = snapshot[AgentConfig.BUDGET]
                BudgetUiState(
                    items = BudgetField.entries.map { field -> BudgetItem(field, field.valueOf(budget)) },
                    editing = editing,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = BudgetUiState(),
            )

        public fun onAction(action: BudgetAction) {
            when (action) {
                is BudgetAction.OpenEditor -> open(action.field)
                // 输入数值即视为设定上限，解除“不限”勾选
                is BudgetAction.SetEditText ->
                    editor.value = editor.value?.copy(text = action.text, unlimited = false, invalid = false)
                is BudgetAction.SetUnlimited ->
                    editor.value = editor.value?.copy(unlimited = action.unlimited, invalid = false)
                BudgetAction.Save -> save()
                BudgetAction.RestoreDefault -> restoreDefault()
                BudgetAction.CloseEditor -> editor.value = null
            }
        }

        private fun open(field: BudgetField) {
            val value = field.valueOf(currentBudget())
            val unlimited = value <= 0
            editor.value =
                BudgetEditState(
                    field = field,
                    unlimited = unlimited,
                    text = if (unlimited) "" else (value / field.unitFactor).toString(),
                )
        }

        /** 保存：不限=0，数值=编辑单位 × [BudgetField.unitFactor]；文本非法置 [BudgetEditState.invalid] 不写入。 */
        private fun save() {
            val state = editor.value ?: return
            val storage =
                if (state.unlimited) {
                    0L
                } else {
                    val units = state.text.trim().toLongOrNull()
                    if (units == null || units < 0) {
                        editor.value = state.copy(invalid = true)
                        return
                    }
                    units * state.field.unitFactor
                }
            editor.value = null
            write(pathOf(state.field), JsonPrimitive(storage))
        }

        /** 恢复默认：清除该维度的用户覆盖（默认 0 = 不限）。 */
        private fun restoreDefault() {
            val field = editor.value?.field ?: return
            editor.value = null
            viewModelScope.launch(dispatchers.io) {
                configEditor.clearOverride(pathOf(field))
            }
        }

        private fun write(
            path: String,
            value: JsonPrimitive,
        ) {
            viewModelScope.launch(dispatchers.io) {
                configEditor.setOverride(path, value).onErr { error ->
                    logger.e(
                        tag = TAG,
                        event = "budget_write_failed",
                        "path" to path,
                        "code" to error.code,
                        error = error.cause,
                    )
                }
            }
        }

        private fun currentBudget(): AgentBudgetSection = configProvider.current()[AgentConfig.BUDGET]

        private fun pathOf(field: BudgetField): String = "${AgentConfig.BUDGET.path}.${field.jsonKey}"

        private companion object {
            const val TAG = "BudgetSettings"
        }
    }
