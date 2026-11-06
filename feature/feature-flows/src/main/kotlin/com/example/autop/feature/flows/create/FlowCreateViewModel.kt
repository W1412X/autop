/**
 * 创建入口 ViewModel：手动创建（空白流程）、导入（粘贴导出的 JSON）。
 */
package com.example.autop.feature.flows.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.FlowBundleService
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowStore
import com.example.autop.flow.ImportConflict
import com.example.autop.flow.IssueSeverity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 结果提示。 */
public sealed interface CreateMessage {
    public data class Imported(
        val names: List<String>,
        val skipped: Int,
    ) : CreateMessage

    public data class Failed(
        val reason: String,
    ) : CreateMessage
}

/** 创建入口状态。 */
public data class FlowCreateUiState(
    val name: String = "",
    val importText: String = "",
    val message: CreateMessage? = null,
    val messageIsError: Boolean = false,
    val missingEndpoints: List<String> = emptyList(),
)

/** 创建入口意图。 */
public sealed interface FlowCreateAction {
    public data object Back : FlowCreateAction

    public data object StartAi : FlowCreateAction

    public data class SetName(
        val value: String,
    ) : FlowCreateAction

    public data object CreateManual : FlowCreateAction

    public data class SetImportText(
        val value: String,
    ) : FlowCreateAction

    public data object Import : FlowCreateAction

    public data object OpenEndpoints : FlowCreateAction
}

/** 创建入口 ViewModel。 */
@HiltViewModel
public class FlowCreateViewModel
    @Inject
    constructor(
        private val store: FlowStore,
        private val bundles: FlowBundleService,
        private val ids: IdGenerator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(FlowCreateUiState())
        public val uiState: StateFlow<FlowCreateUiState> = _uiState

        public fun onAction(
            action: FlowCreateAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                FlowCreateAction.Back -> navigator.navigateBack()
                FlowCreateAction.StartAi -> navigator.openAuthor(null, null)
                is FlowCreateAction.SetName -> _uiState.update { it.copy(name = action.value) }
                FlowCreateAction.CreateManual -> createManual(navigator)
                is FlowCreateAction.SetImportText -> _uiState.update { it.copy(importText = action.value) }
                FlowCreateAction.Import -> import()
                FlowCreateAction.OpenEndpoints -> navigator.openEndpoints()
            }
        }

        private fun createManual(navigator: FlowsNavigator) {
            val name = _uiState.value.name.trim()
            if (name.isEmpty()) return
            viewModelScope.launch {
                val flow = FlowDefinition(id = "flow_" + ids.newId(), name = name)
                when (val saved = store.save(flow)) {
                    is Outcome.Ok -> navigator.openEditor(saved.value.id.value)
                    is Outcome.Err ->
                        _uiState.update {
                            it.copy(
                                message = CreateMessage.Failed(saved.error.code),
                                messageIsError = true,
                            )
                        }
                }
            }
        }

        private fun import() {
            val text = _uiState.value.importText
            viewModelScope.launch {
                when (val parsed = bundles.parse(text)) {
                    is Outcome.Err ->
                        _uiState.update {
                            it.copy(
                                message = CreateMessage.Failed(parsed.error.message),
                                messageIsError = true,
                            )
                        }
                    is Outcome.Ok -> {
                        val report = bundles.import(parsed.value, ImportConflict.KEEP_BOTH)
                        val errors = report.issues.firstOrNull { it.severity == IssueSeverity.ERROR }
                        _uiState.update {
                            it.copy(
                                message =
                                    if (report.imported.isEmpty() && errors != null) {
                                        CreateMessage.Failed(errors.message)
                                    } else {
                                        CreateMessage.Imported(report.imported, report.skipped.size)
                                    },
                                messageIsError = report.imported.isEmpty(),
                                missingEndpoints = report.missingEndpoints.map { e -> e.name },
                                importText = if (report.imported.isEmpty()) it.importText else "",
                            )
                        }
                    }
                }
            }
        }
    }
