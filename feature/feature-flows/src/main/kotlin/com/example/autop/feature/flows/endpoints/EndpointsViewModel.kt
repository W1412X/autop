/**
 * 接口登记：流程的 api 节点只能访问这里登记过的地址（docs/design/flows.md §11）。
 */
package com.example.autop.feature.flows.endpoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.Outcome
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.flow.ApiEndpoint
import com.example.autop.flow.EndpointStore
import com.example.autop.flow.EndpointTestResult
import com.example.autop.flow.EndpointTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑中的接口表单。 */
public data class EndpointForm(
    val originalName: String?,
    val name: String = "",
    val baseUrl: String = "",
    /** 新密钥；留空表示不改动已保存的密钥。 */
    val secret: String = "",
    val hasSecret: Boolean = false,
    val secretHeader: String = "Authorization",
    val secretPrefix: String = "Bearer ",
    /** 每行一个 `名称: 值`。 */
    val headers: String = "",
    val enabled: Boolean = true,
    val testPath: String = "",
    val testing: Boolean = false,
    val testResult: EndpointTestResult? = null,
    val error: String? = null,
)

/** 接口页状态。 */
public data class EndpointsUiState(
    val endpoints: List<ApiEndpoint> = emptyList(),
    val form: EndpointForm? = null,
    val pendingDelete: String? = null,
)

/** 接口页意图。 */
public sealed interface EndpointsAction {
    public data object Back : EndpointsAction

    public data object Add : EndpointsAction

    public data class Edit(
        val name: String,
    ) : EndpointsAction

    public data class Change(
        val form: EndpointForm,
    ) : EndpointsAction

    public data object Save : EndpointsAction

    public data object Test : EndpointsAction

    public data object Dismiss : EndpointsAction

    public data class RequestDelete(
        val name: String,
    ) : EndpointsAction

    public data object ConfirmDelete : EndpointsAction

    public data object DismissDelete : EndpointsAction
}

/** 接口页 ViewModel。 */
@HiltViewModel
public class EndpointsViewModel
    @Inject
    constructor(
        private val store: EndpointStore,
        private val tester: EndpointTester,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(EndpointsUiState())
        public val uiState: StateFlow<EndpointsUiState> = _uiState

        init {
            viewModelScope.launch {
                store.observeAll().collect { list ->
                    _uiState.update { it.copy(endpoints = list) }
                }
            }
        }

        public fun onAction(
            action: EndpointsAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                EndpointsAction.Back -> navigator.navigateBack()
                EndpointsAction.Add -> _uiState.update { it.copy(form = EndpointForm(originalName = null)) }
                is EndpointsAction.Edit -> edit(action.name)
                is EndpointsAction.Change -> _uiState.update { it.copy(form = action.form) }
                EndpointsAction.Save -> save()
                EndpointsAction.Test -> test()
                EndpointsAction.Dismiss -> _uiState.update { it.copy(form = null) }
                is EndpointsAction.RequestDelete -> _uiState.update { it.copy(pendingDelete = action.name) }
                EndpointsAction.DismissDelete -> _uiState.update { it.copy(pendingDelete = null) }
                EndpointsAction.ConfirmDelete -> {
                    val name = _uiState.value.pendingDelete ?: return
                    _uiState.update { it.copy(pendingDelete = null) }
                    viewModelScope.launch { store.delete(name) }
                }
            }
        }

        private fun edit(name: String) {
            val e = _uiState.value.endpoints.firstOrNull { it.name == name } ?: return
            _uiState.update {
                it.copy(
                    form =
                        EndpointForm(
                            originalName = e.name,
                            name = e.name,
                            baseUrl = e.baseUrl,
                            hasSecret = e.secretRef != null,
                            secretHeader = e.secretHeader,
                            secretPrefix = e.secretPrefix,
                            headers = e.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" },
                            enabled = e.enabled,
                        ),
                )
            }
        }

        private fun save() {
            val form = _uiState.value.form ?: return
            if (form.name.isBlank() || form.baseUrl.isBlank()) {
                _uiState.update { it.copy(form = form.copy(error = ERROR_REQUIRED)) }
                return
            }
            viewModelScope.launch {
                val existing = form.originalName?.let { store.get(it) }
                val endpoint =
                    ApiEndpoint(
                        name = form.name.trim(),
                        baseUrl = form.baseUrl.trim(),
                        headers = parseHeaders(form.headers),
                        secretRef = existing?.secretRef,
                        secretHeader = form.secretHeader.ifBlank { "Authorization" },
                        secretPrefix = form.secretPrefix,
                        enabled = form.enabled,
                    )
                if (form.originalName != null && form.originalName != endpoint.name) store.delete(form.originalName)
                when (val saved = store.save(endpoint, form.secret.takeIf { it.isNotBlank() })) {
                    is Outcome.Ok -> _uiState.update { it.copy(form = null) }
                    is Outcome.Err -> _uiState.update { it.copy(form = form.copy(error = saved.error.code)) }
                }
            }
        }

        private fun test() {
            val form = _uiState.value.form ?: return
            if (form.originalName == null) {
                _uiState.update { it.copy(form = form.copy(error = ERROR_SAVE_FIRST)) }
                return
            }
            _uiState.update { it.copy(form = form.copy(testing = true, testResult = null, error = null)) }
            viewModelScope.launch {
                val result = tester.test(form.originalName, form.testPath)
                _uiState.update { s -> s.copy(form = s.form?.copy(testing = false, testResult = result)) }
            }
        }

        private fun parseHeaders(text: String): Map<String, String> =
            text
                .lines()
                .mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }.toMap()

        public companion object {
            public const val ERROR_REQUIRED: String = "required"
            public const val ERROR_SAVE_FIRST: String = "save_first"
        }
    }
