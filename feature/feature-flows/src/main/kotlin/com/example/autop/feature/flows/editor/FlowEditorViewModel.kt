/**
 * 流程编辑器 ViewModel：简明 / 高级 / 原始 JSON 三种视图共用同一份工作副本，
 * 每次改动都重新校验；运行与调试（从某节点开始、只运行某节点、断点）先保存再启动。
 */
@file:Suppress("CyclomaticComplexMethod") // 意图分发表

package com.example.autop.feature.flows.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.FlowId
import com.example.autop.core.common.Outcome
import com.example.autop.feature.flows.FLOW_ID_ARG
import com.example.autop.feature.flows.FlowsNavigator
import com.example.autop.feature.flows.missingRequired
import com.example.autop.feature.flows.paramsJson
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowIssue
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowStore
import com.example.autop.flow.FlowValidationService
import com.example.autop.flow.IssueSeverity
import com.example.autop.flow.Target
import com.example.autop.flow.TargetPicker
import com.example.autop.task.RunService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** 编辑视图。 */
public enum class EditorMode {
    SIMPLE,
    ADVANCED,
    JSON,
}

/** 正在编辑的节点。 */
public data class NodeEditUi(
    val nodeId: String,
    val type: String,
    val draft: JsonObject,
    val isNew: Boolean,
    val error: String? = null,
)

/** 运行对话框（完整运行 / 从节点开始 / 只运行节点）。 */
public data class EditorRunUi(
    val startAt: String?,
    val onlyNode: String?,
    val params: Map<String, String>,
    /** 起点变量覆盖（JSON 对象文本），供 startAt / onlyNode 使用。 */
    val overrides: String = "",
    val debug: Boolean = true,
    val error: String? = null,
)

/** 流程级设置的编辑副本（JSON 文本）。 */
public data class FlowSettingsUi(
    val name: String,
    val description: String,
    val params: String,
    val settings: String,
    val error: Boolean = false,
)

/** 编辑器状态。 */
public data class FlowEditorUiState(
    val loaded: Boolean = false,
    val flow: FlowDefinition? = null,
    val mode: EditorMode = EditorMode.SIMPLE,
    val issues: List<FlowIssue> = emptyList(),
    val dirty: Boolean = false,
    val editing: NodeEditUi? = null,
    val menuNodeId: String? = null,
    val addSlot: ListSlot? = null,
    val jsonText: String = "",
    val jsonError: Boolean = false,
    val breakpoints: Set<String> = emptySet(),
    val run: EditorRunUi? = null,
    val settings: FlowSettingsUi? = null,
    val confirmDiscard: Boolean = false,
    val showIssues: Boolean = false,
    val error: String? = null,
)

/** 编辑器意图。 */
public sealed interface FlowEditorAction {
    public data object Back : FlowEditorAction

    public data object ConfirmDiscard : FlowEditorAction

    public data object DismissDiscard : FlowEditorAction

    public data class SetMode(
        val mode: EditorMode,
    ) : FlowEditorAction

    public data object Save : FlowEditorAction

    public data class EditNode(
        val nodeId: String,
    ) : FlowEditorAction

    public data class ChangeDraft(
        val draft: JsonObject,
    ) : FlowEditorAction

    /** 在手机上重新选取目标元素；结果以 JSON 形式交给 [onPicked]。 */
    public data class ReselectTarget(
        val onPicked: (JsonElement) -> Unit,
    ) : FlowEditorAction

    public data object ApplyNode : FlowEditorAction

    public data object CancelNode : FlowEditorAction

    public data class OpenMenu(
        val nodeId: String,
    ) : FlowEditorAction

    public data object DismissMenu : FlowEditorAction

    public data class Menu(
        val nodeId: String,
        val item: String,
    ) : FlowEditorAction

    public data class RequestAdd(
        val slot: ListSlot,
    ) : FlowEditorAction

    public data class AddType(
        val type: String,
    ) : FlowEditorAction

    public data object DismissAdd : FlowEditorAction

    public data class SetJson(
        val text: String,
    ) : FlowEditorAction

    public data object OpenSettings : FlowEditorAction

    public data class ChangeSettings(
        val settings: FlowSettingsUi,
    ) : FlowEditorAction

    public data object ApplySettings : FlowEditorAction

    public data object DismissSettings : FlowEditorAction

    public data object ToggleIssues : FlowEditorAction

    public data object RunWhole : FlowEditorAction

    public data class ChangeRun(
        val run: EditorRunUi,
    ) : FlowEditorAction

    public data object ConfirmRun : FlowEditorAction

    public data object DismissRun : FlowEditorAction
}

/** 节点菜单项 id。 */
internal object MenuItems {
    const val EDIT = "edit"
    const val UP = "up"
    const val DOWN = "down"
    const val DUPLICATE = "duplicate"
    const val DELETE = "delete"
    const val TOGGLE_ENABLED = "toggle_enabled"
    const val ADD_AFTER = "add_after"
    const val BREAKPOINT = "breakpoint"
    const val RUN_FROM = "run_from"
    const val RUN_ONLY = "run_only"
}

/** 编辑器 ViewModel。 */
@HiltViewModel
public class FlowEditorViewModel
    @Inject
    @Suppress("LongParameterList") // ViewModel 的依赖注入面
    constructor(
        savedStateHandle: SavedStateHandle,
        private val store: FlowStore,
        private val validation: FlowValidationService,
        private val runService: RunService,
        private val targetPicker: TargetPicker,
    ) : ViewModel() {
        private val flowId: String = savedStateHandle.get<String>(FLOW_ID_ARG).orEmpty()
        private val json = AutopJson.default
        private val pretty = Json(from = AutopJson.default) { prettyPrint = true }
        private val _uiState = MutableStateFlow(FlowEditorUiState())
        public val uiState: StateFlow<FlowEditorUiState> = _uiState
        private var validateJob: Job? = null

        init {
            viewModelScope.launch {
                val flow = store.get(FlowId(flowId))?.definition
                _uiState.update {
                    it.copy(
                        loaded = true,
                        flow = flow,
                        jsonText = flow?.let(::render).orEmpty(),
                        error =
                            if (flow ==
                                null
                            ) {
                                NOT_FOUND
                            } else {
                                null
                            },
                    )
                }
                flow?.let { revalidate(it) }
            }
        }

        private fun reselect(onPicked: (JsonElement) -> Unit) {
            viewModelScope.launch {
                val picked = targetPicker.pickTarget()
                if (picked is Outcome.Ok) onPicked(json.encodeToJsonElement(Target.serializer(), picked.value))
            }
        }

        public fun onAction(
            action: FlowEditorAction,
            navigator: FlowsNavigator,
        ) {
            when (action) {
                FlowEditorAction.Back ->
                    if (_uiState.value.dirty) {
                        _uiState.update {
                            it.copy(confirmDiscard = true)
                        }
                    } else {
                        navigator.navigateBack()
                    }
                FlowEditorAction.ConfirmDiscard -> navigator.navigateBack()
                is FlowEditorAction.ReselectTarget -> reselect(action.onPicked)
                FlowEditorAction.DismissDiscard -> _uiState.update { it.copy(confirmDiscard = false) }
                is FlowEditorAction.SetMode -> _uiState.update { it.copy(mode = action.mode) }
                FlowEditorAction.Save -> save(navigator, thenRun = false)
                is FlowEditorAction.EditNode -> startEdit(action.nodeId)
                is FlowEditorAction.ChangeDraft ->
                    _uiState.update { s ->
                        s.copy(editing = s.editing?.copy(draft = action.draft, error = null))
                    }
                FlowEditorAction.ApplyNode -> applyNode()
                FlowEditorAction.CancelNode -> cancelNode()
                is FlowEditorAction.OpenMenu -> _uiState.update { it.copy(menuNodeId = action.nodeId) }
                FlowEditorAction.DismissMenu -> _uiState.update { it.copy(menuNodeId = null) }
                is FlowEditorAction.Menu -> menu(action.nodeId, action.item)
                is FlowEditorAction.RequestAdd -> _uiState.update { it.copy(addSlot = action.slot) }
                is FlowEditorAction.AddType -> addNode(action.type)
                FlowEditorAction.DismissAdd -> _uiState.update { it.copy(addSlot = null) }
                is FlowEditorAction.SetJson -> setJson(action.text)
                FlowEditorAction.OpenSettings -> openSettings()
                is FlowEditorAction.ChangeSettings ->
                    _uiState.update {
                        it.copy(
                            settings = action.settings.copy(error = false),
                        )
                    }
                FlowEditorAction.ApplySettings -> applySettings()
                FlowEditorAction.DismissSettings -> _uiState.update { it.copy(settings = null) }
                FlowEditorAction.ToggleIssues -> _uiState.update { it.copy(showIssues = !it.showIssues) }
                FlowEditorAction.RunWhole -> openRun(null, null)
                is FlowEditorAction.ChangeRun -> _uiState.update { it.copy(run = action.run.copy(error = null)) }
                FlowEditorAction.ConfirmRun -> save(navigator, thenRun = true)
                FlowEditorAction.DismissRun -> _uiState.update { it.copy(run = null) }
            }
        }

        // ---- 工作副本 -----------------------------------------------------------------------------

        private fun render(flow: FlowDefinition): String = pretty.encodeToString(FlowDefinition.serializer(), flow)

        private fun update(flow: FlowDefinition) {
            _uiState.update { it.copy(flow = flow, dirty = true, jsonText = render(flow), jsonError = false) }
            revalidate(flow)
        }

        private fun revalidate(flow: FlowDefinition) {
            validateJob?.cancel()
            validateJob = viewModelScope.launch { _uiState.update { it.copy(issues = validation.validate(flow)) } }
        }

        // ---- 节点编辑 -----------------------------------------------------------------------------

        private fun startEdit(nodeId: String) {
            val flow = _uiState.value.flow ?: return
            val node = FlowTreeOps.find(flow, nodeId) ?: return
            val obj = NodeJson.encode(node)
            val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            _uiState.update { it.copy(editing = NodeEditUi(nodeId, type, obj, isNew = false), menuNodeId = null) }
        }

        private fun applyNode() {
            val s = _uiState.value
            val edit = s.editing ?: return
            val flow = s.flow ?: return
            val node =
                try {
                    NodeJson.decode(edit.draft)
                } catch (e: IllegalArgumentException) {
                    _uiState.update { it.copy(editing = edit.copy(error = e.message.orEmpty())) }
                    return
                }
            update(FlowTreeOps.replace(flow, edit.nodeId, node))
            _uiState.update { it.copy(editing = null) }
        }

        /** 取消：新建且未确认的节点已经在树里（添加时先放入），取消即移除。 */
        private fun cancelNode() {
            val s = _uiState.value
            val edit = s.editing
            val flow = s.flow
            if (edit != null && edit.isNew && flow != null) update(FlowTreeOps.remove(flow, edit.nodeId))
            _uiState.update { it.copy(editing = null) }
        }

        private fun addNode(type: String) {
            val s = _uiState.value
            val flow = s.flow ?: return
            val slot = s.addSlot ?: return
            val node = FlowTreeOps.newNode(type, FlowTreeOps.nextId(flow))
            update(
                if (slot.afterId !=
                    null
                ) {
                    FlowTreeOps.insertAfter(flow, slot.afterId, node)
                } else {
                    FlowTreeOps.append(flow, slot, node)
                },
            )
            _uiState.update {
                it.copy(
                    addSlot = null,
                    editing = NodeEditUi(node.id, type, NodeJson.encode(node), isNew = true),
                )
            }
        }

        private fun menu(
            nodeId: String,
            item: String,
        ) {
            val flow = _uiState.value.flow ?: return
            _uiState.update { it.copy(menuNodeId = null) }
            when (item) {
                MenuItems.EDIT -> startEdit(nodeId)
                MenuItems.UP -> update(FlowTreeOps.move(flow, nodeId, -1))
                MenuItems.DOWN -> update(FlowTreeOps.move(flow, nodeId, 1))
                MenuItems.DELETE -> update(FlowTreeOps.remove(flow, nodeId))
                MenuItems.DUPLICATE ->
                    FlowTreeOps
                        .duplicate(
                            flow,
                            nodeId,
                        )?.let { update(FlowTreeOps.insertAfter(flow, nodeId, it)) }
                MenuItems.TOGGLE_ENABLED -> toggleEnabled(flow, nodeId)
                MenuItems.ADD_AFTER -> _uiState.update { it.copy(addSlot = ListSlot(null, "root", afterId = nodeId)) }
                MenuItems.BREAKPOINT ->
                    _uiState.update { s ->
                        s.copy(
                            breakpoints =
                                if (nodeId in
                                    s.breakpoints
                                ) {
                                    s.breakpoints - nodeId
                                } else {
                                    s.breakpoints + nodeId
                                },
                        )
                    }
                MenuItems.RUN_FROM -> openRun(startAt = nodeId, onlyNode = null)
                MenuItems.RUN_ONLY -> openRun(startAt = null, onlyNode = nodeId)
            }
        }

        private fun toggleEnabled(
            flow: FlowDefinition,
            nodeId: String,
        ) {
            val node = FlowTreeOps.find(flow, nodeId) ?: return
            val obj = NodeJson.encode(node)
            val enabled = (obj["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
            val next = JsonObject(obj + ("enabled" to kotlinx.serialization.json.JsonPrimitive(!enabled)))
            update(FlowTreeOps.replace(flow, nodeId, NodeJson.decode(next)))
        }

        // ---- 原始 JSON ----------------------------------------------------------------------------

        private fun setJson(text: String) {
            _uiState.update { it.copy(jsonText = text) }
            val parsed = runCatching { json.decodeFromString(FlowDefinition.serializer(), text) }.getOrNull()
            if (parsed == null) {
                _uiState.update { it.copy(jsonError = true) }
                return
            }
            _uiState.update { it.copy(flow = parsed, dirty = true, jsonError = false) }
            revalidate(parsed)
        }

        // ---- 流程级设置 ---------------------------------------------------------------------------

        private fun openSettings() {
            val flow = _uiState.value.flow ?: return
            _uiState.update {
                it.copy(
                    settings =
                        FlowSettingsUi(
                            name = flow.name,
                            description = flow.description,
                            params =
                                pretty.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(
                                        com.example.autop.flow.FlowParam
                                            .serializer(),
                                    ),
                                    flow.params,
                                ),
                            settings =
                                pretty.encodeToString(
                                    com.example.autop.flow.FlowSettings
                                        .serializer(),
                                    flow.settings,
                                ),
                        ),
                )
            }
        }

        private fun applySettings() {
            val s = _uiState.value
            val form = s.settings ?: return
            val flow = s.flow ?: return
            val params =
                runCatching {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(
                            com.example.autop.flow.FlowParam
                                .serializer(),
                        ),
                        form.params,
                    )
                }.getOrNull()
            val settings =
                runCatching {
                    json.decodeFromString(
                        com.example.autop.flow.FlowSettings
                            .serializer(),
                        form.settings,
                    )
                }.getOrNull()
            if (params == null || settings == null || form.name.isBlank()) {
                _uiState.update { it.copy(settings = form.copy(error = true)) }
                return
            }
            update(
                flow.copy(
                    name = form.name.trim(),
                    description = form.description,
                    params = params,
                    settings = settings,
                ),
            )
            _uiState.update { it.copy(settings = null) }
        }

        // ---- 保存与运行 ---------------------------------------------------------------------------

        private fun openRun(
            startAt: String?,
            onlyNode: String?,
        ) {
            val flow = _uiState.value.flow ?: return
            val defaults =
                flow.params
                    .mapNotNull { p ->
                        p.default?.let { d ->
                            p.name to
                                com.example.autop.feature.flows
                                    .textOf(d)
                        }
                    }.toMap()
            _uiState.update { it.copy(run = EditorRunUi(startAt, onlyNode, defaults)) }
        }

        private fun save(
            navigator: FlowsNavigator,
            thenRun: Boolean,
        ) {
            val s = _uiState.value
            val flow = s.flow ?: return
            if (s.jsonError) return
            viewModelScope.launch {
                val issues = validation.validate(flow)
                _uiState.update { it.copy(issues = issues) }
                if (issues.any { it.severity == IssueSeverity.ERROR }) {
                    _uiState.update { it.copy(error = HAS_ERRORS, showIssues = true) }
                    return@launch
                }
                when (val saved = store.save(flow)) {
                    is Outcome.Err -> _uiState.update { it.copy(error = saved.error.code) }
                    is Outcome.Ok -> {
                        _uiState.update { it.copy(dirty = false, error = null) }
                        if (thenRun) startRun(navigator) else navigator.navigateBack()
                    }
                }
            }
        }

        private suspend fun startRun(navigator: FlowsNavigator) {
            val s = _uiState.value
            val flow = s.flow ?: return
            val run = s.run ?: return
            if (missingRequired(flow.params, run.params)) {
                _uiState.update { it.copy(run = run.copy(error = MISSING_PARAM)) }
                return
            }
            val overrides =
                if (run.overrides.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    runCatching { Json.parseToJsonElement(run.overrides) as? JsonObject }.getOrNull()
                        ?: run {
                            _uiState.update { it.copy(run = run.copy(error = BAD_OVERRIDES)) }
                            return
                        }
                }
            val options =
                FlowRunOptions(
                    debug = run.debug,
                    breakpoints = s.breakpoints,
                    startAt = run.startAt,
                    onlyNode = run.onlyNode,
                    overrides = overrides,
                )
            when (val result = runService.runFlow(FlowId(flow.id), paramsJson(flow.params, run.params), options)) {
                is Outcome.Ok -> {
                    _uiState.update { it.copy(run = null) }
                    navigator.openRunRecord(result.value.id.value)
                }
                is Outcome.Err -> _uiState.update { it.copy(run = run.copy(error = result.error.code)) }
            }
        }

        public companion object {
            public const val NOT_FOUND: String = "not_found"
            public const val HAS_ERRORS: String = "has_errors"
            public const val MISSING_PARAM: String = "missing_param"
            public const val BAD_OVERRIDES: String = "bad_overrides"
        }
    }
