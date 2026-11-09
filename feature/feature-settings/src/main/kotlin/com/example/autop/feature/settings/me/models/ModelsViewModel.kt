/**
 * 模型配置页 ViewModel（docs/tech/08 §4.5、docs/standards/coding-standards §8）。
 *
 * 列表数据来自 [ModelProfilesEditor]（ModelProfileStore 流）；编辑草稿落库时同时
 * 经 SecretStore 写 API Key；测试连接经 [ModelProbe] 回填 `ModelCapabilities`。
 */
package com.example.autop.feature.settings.me.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.AppError
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.flatMap
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelProbe
import com.example.autop.llm.ModelRole
import com.example.autop.llm.ProbeReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 模型配置页 ViewModel（端口注入面 + 动作分发是自然结果，N164 起超阈值豁免）。 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
public class ModelsViewModel
    @Inject
    constructor(
        private val editor: ModelProfilesEditor,
        private val probe: ModelProbe,
        private val directory: ModelDirectory,
        configProvider: ConfigProvider,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        private val draftState = MutableStateFlow<ModelDraft?>(null)
        private val probeState = MutableStateFlow(ProbeUiState())
        private var probeJob: Job? = null
        private val remoteModelsState = MutableStateFlow(RemoteModelsUiState())
        private val errorState = MutableStateFlow<ModelsErrorKind?>(null)
        private val noticeState = MutableStateFlow<ModelsNoticeKind?>(null)
        private val confirmDeleteState = MutableStateFlow(false)

        public val uiState: StateFlow<ModelsUiState> =
            combine(
                combine(editor.profiles, editor.roles) { list, roles -> list to roles },
                combine(draftState, probeState, remoteModelsState) { draft, probe, remote ->
                    Triple(draft, probe, remote)
                },
                combine(errorState, noticeState, confirmDeleteState) { error, notice, confirm ->
                    Triple(error, notice, confirm)
                },
                configProvider.observe(),
            ) { (profiles, roles), (draft, probe, remote), (error, notice, confirm), snapshot ->
                ModelsUiState(
                    profiles = profiles,
                    roles = roles,
                    draft = draft,
                    probe = probe,
                    remoteModels = remote,
                    error = error,
                    notice = notice,
                    confirmDelete = confirm,
                    bannerDurationMs = snapshot[UiConfig.BANNER].durationMs,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ModelsUiState(),
            )

        public fun onAction(action: ModelsAction) {
            when (action) {
                is ModelsAction.SetField -> mutate { it.withField(action.field, action.value) }
                is ModelsAction.SetQuirk -> mutate { it.withQuirk(action.key, action.value) }
                is ModelsAction.AssignRole -> assignRole(action.role, action.profileId)
                is ModelsAction.Dismiss -> dismiss(action.kind)
                ModelsAction.ToggleQuirks -> mutate { it.copy(quirksExpanded = !it.quirksExpanded) }
                ModelsAction.New -> newDraft()
                is ModelsAction.Edit -> openDraft(action.id)
                ModelsAction.Cancel, ModelsAction.CloseProbe -> closeDraft()
                ModelsAction.Save -> save()
                ModelsAction.RetestProbe -> retest()
                ModelsAction.FetchRemoteModels -> fetchRemoteModels()
                ModelsAction.RequestDelete -> confirmDeleteState.value = true
                ModelsAction.ConfirmDelete -> deleteProfile()
            }
        }

        private fun newDraft() {
            errorState.value = null
            noticeState.value = null
            probeState.value = ProbeUiState()
            remoteModelsState.value = RemoteModelsUiState()
            draftState.value =
                ModelDraft(
                    id = editor.newId(),
                    isNew = true,
                    protocol = DEFAULT_PROTOCOL,
                    displayName = "",
                    baseUrl = "",
                    model = "",
                    contextWindow = "",
                    maxOutputTokens = "",
                )
        }

        private fun openDraft(id: String) {
            errorState.value = null
            noticeState.value = null
            probeState.value = ProbeUiState()
            remoteModelsState.value = RemoteModelsUiState()
            viewModelScope.launch(dispatchers.io) {
                val profile = editor.profile(id) ?: return@launch
                draftState.value = profile.toDraft(editor.savedKeyTail(id))
            }
        }

        private fun mutate(transform: (ModelDraft) -> ModelDraft) {
            val draft = draftState.value ?: return
            errorState.value = null
            draftState.value = transform(draft)
        }

        private fun save() {
            val draft = draftState.value ?: return
            val problem = draft.validationError()
            if (problem != null) {
                errorState.value = problem
                return
            }
            viewModelScope.launch(dispatchers.io) {
                val outcome =
                    runCatchingCancellable(CommonErrorCodes.IO) {
                        editor.saveProfile(draft.toProfile(), draft.apiKeyInput)
                    }
                when (outcome) {
                    is Outcome.Ok -> startProbe(draft)
                    is Outcome.Err -> fail("model_save_failed", outcome.error)
                }
            }
        }

        /** 关闭草稿（返回列表），同时取消进行中的自动测试。 */
        private fun closeDraft() {
            probeJob?.cancel()
            probeState.value = ProbeUiState()
            remoteModelsState.value = RemoteModelsUiState()
            draftState.value = null
        }

        /** 保存/重测共用：落库后自动测试连接（docs/tech/08 §4.5），结果驱动弹窗。 */
        private fun startProbe(draft: ModelDraft) {
            probeJob?.cancel()
            probeState.value = ProbeUiState(visible = true, running = true)
            val profile = draft.toProfile()
            probeJob =
                viewModelScope.launch(dispatchers.io) {
                    val report = runCatchingCancellable(CommonErrorCodes.IO) { probe.probe(profile) }
                    when (report) {
                        is Outcome.Ok -> onProbed(draft, report.value)
                        is Outcome.Err -> {
                            logger.e(
                                tag = TAG,
                                event = "model_probe_failed",
                                "code" to report.error.code,
                                error = report.error.cause,
                            )
                            probeState.value = ProbeUiState(visible = true, error = report.error)
                        }
                    }
                }
        }

        /** 回填并持久化探测出的能力（tools / vision），弹窗展示逐项结果。 */
        private suspend fun onProbed(
            draft: ModelDraft,
            report: ProbeReport,
        ) {
            val updated = draft.withProbed(report, editor.savedKeyTail(draft.id))
            draftState.value = updated
            probeState.value = ProbeUiState(visible = true, report = report)
            val persisted =
                runCatchingCancellable(CommonErrorCodes.IO) { editor.saveProfile(updated.toProfile(), null) }
            if (persisted is Outcome.Err) {
                logger.e(
                    tag = TAG,
                    event = "model_capabilities_save_failed",
                    "code" to persisted.error.code,
                    error = persisted.error.cause,
                )
            }
        }

        private fun retest() {
            val draft = draftState.value ?: return
            startProbe(draft)
        }

        private fun deleteProfile() {
            val draft = draftState.value ?: return
            confirmDeleteState.value = false
            viewModelScope.launch(dispatchers.io) {
                val outcome =
                    runCatchingCancellable(CommonErrorCodes.IO) { editor.deleteProfile(draft.id) }
                when (outcome) {
                    is Outcome.Ok -> {
                        probeJob?.cancel()
                        probeState.value = ProbeUiState()
                        draftState.value = null
                    }
                    is Outcome.Err -> fail("model_delete_failed", outcome.error)
                }
            }
        }

        private fun fetchRemoteModels() {
            val draft = draftState.value ?: return
            val problem = draft.fetchModelsError()
            if (problem != null) {
                errorState.value = problem
                return
            }
            viewModelScope.launch(dispatchers.io) {
                remoteModelsState.value = RemoteModelsUiState(running = true)
                // 先落密钥再拉取（适配器经 `apiKeyRef` 解析密钥，同测试连接）。
                val outcome =
                    runCatchingCancellable(CommonErrorCodes.IO) {
                        if (draft.apiKeyInput.isNotBlank()) {
                            editor.saveKey(draft.id, draft.apiKeyInput)
                        }
                        directory.listModels(draft.toProfile())
                    }.flatMap { it }
                when (outcome) {
                    is Outcome.Ok -> {
                        remoteModelsState.value = RemoteModelsUiState(models = outcome.value)
                        if (outcome.value.isEmpty()) noticeState.value = ModelsNoticeKind.MODELS_EMPTY
                    }
                    is Outcome.Err -> {
                        remoteModelsState.value = RemoteModelsUiState()
                        fail("model_list_failed", outcome.error, ModelsErrorKind.LIST_MODELS_FAILED)
                    }
                }
            }
        }

        private fun assignRole(
            role: ModelRole,
            profileId: String?,
        ) {
            val current = uiState.value.roles
            viewModelScope.launch(dispatchers.io) {
                val outcome =
                    runCatchingCancellable(CommonErrorCodes.IO) {
                        editor.setRoles(
                            when (role) {
                                ModelRole.MAIN -> current.copy(main = profileId)
                                ModelRole.VISION -> current.copy(vision = profileId)
                                ModelRole.LIGHT -> current.copy(light = profileId)
                            },
                        )
                    }
                if (outcome is Outcome.Err) fail("model_role_failed", outcome.error)
            }
        }

        private fun dismiss(kind: DismissKind) {
            when (kind) {
                DismissKind.ERROR -> errorState.value = null
                DismissKind.NOTICE -> noticeState.value = null
                DismissKind.DELETE_CONFIRM -> confirmDeleteState.value = false
            }
        }

        private fun fail(
            event: String,
            error: AppError,
            kind: ModelsErrorKind = ModelsErrorKind.SAVE_FAILED,
        ) {
            logger.e(tag = TAG, event = event, "code" to error.code, error = error.cause)
            errorState.value = kind
        }

        private companion object {
            const val TAG = "ModelsSettings"

            /** 默认协议（OpenAI 兼容，docs/tech/03 §5.1）。 */
            const val DEFAULT_PROTOCOL = "openai"
        }
    }
