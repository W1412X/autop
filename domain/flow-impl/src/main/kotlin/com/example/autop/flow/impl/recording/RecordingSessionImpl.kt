/**
 * 流程录制会话（docs/design/flows-recording-recovery.md V1、V4、V5）：收集用户在目标应用中的操作，
 * 完成时转换为待确认草稿；也可以不保存，直接试运行草稿。同一时间只允许一个会话。
 */
package com.example.autop.flow.impl.recording

import com.example.autop.agent.RunOutcome
import com.example.autop.agent.ThreadStore
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FlowConfig
import com.example.autop.device.PickedElement
import com.example.autop.device.ScreenReader
import com.example.autop.device.UserAction
import com.example.autop.device.UserActionMonitor
import com.example.autop.device.UserActionType
import com.example.autop.flow.FlowDefinition
import com.example.autop.flow.FlowErrorCodes
import com.example.autop.flow.FlowRunOptions
import com.example.autop.flow.FlowRunRequest
import com.example.autop.flow.FlowSession
import com.example.autop.flow.Node
import com.example.autop.flow.PickKind
import com.example.autop.flow.PolishedName
import com.example.autop.flow.RecordedStep
import com.example.autop.flow.RecordingSession
import com.example.autop.flow.RecordingState
import com.example.autop.flow.ToolNode
import com.example.autop.flow.TrialScope
import com.example.autop.flow.TypedInput
import com.example.autop.flow.core.ParamBinder
import com.example.autop.flow.core.recording.RecordingConverter
import com.example.autop.flow.core.recording.RecordingDraft
import com.example.autop.flow.core.recording.RecordingOptions
import com.example.autop.flow.core.recording.ScreenSize
import com.example.autop.flow.impl.author.FlowDraftWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 录制会话实现。 */
@Singleton
public class RecordingSessionImpl
    @Inject
    internal constructor(
        private val monitor: UserActionMonitor,
        private val screens: ScreenReader,
        private val writer: FlowDraftWriter,
        private val configProvider: ConfigProvider,
        private val ids: IdGenerator,
        private val dispatchers: DispatcherProvider,
        private val clock: Clock,
        private val session: FlowSession,
        private val threads: ThreadStore,
        private val polisher: NamePolisher,
    ) : RecordingSession {
        private val lock = Any()
        private val actions = mutableListOf<UserAction>()
        private val stateFlow = MutableStateFlow(RecordingState())
        private var scope: CoroutineScope? = null

        override val state: StateFlow<RecordingState> = stateFlow.asStateFlow()

        override fun start() {
            if (stateFlow.value.active) return
            synchronized(lock) { actions.clear() }
            val s = CoroutineScope(SupervisorJob() + dispatchers.default)
            scope = s
            // UNDISPATCHED：在开始采集前完成订阅，避免丢掉最先到达的操作。
            s.launch(start = CoroutineStart.UNDISPATCHED) {
                monitor.actions.collect { action ->
                    synchronized(lock) { actions += action }
                    publish()
                }
            }
            monitor.start()
            stateFlow.value = RecordingState(active = true)
        }

        override fun undo() {
            synchronized(lock) {
                if (actions.isNotEmpty()) actions.removeAt(actions.lastIndex)
            }
            publish()
        }

        override suspend fun finish(
            name: String,
            paramNames: Map<String, String>,
        ): Outcome<String> {
            val draft =
                when (val built = buildDraft(name, paramNames)) {
                    is Outcome.Err -> return built
                    is Outcome.Ok -> built.value
                }
            return writer.save(draft)
        }

        override suspend fun trialRun(scope: TrialScope): Outcome<RunOutcome> {
            val draft = synchronized(lock) { actions.toList() }
            if (draft.isEmpty()) return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "没有可试运行的操作"))
            val built =
                when (val r = buildDraft(TRIAL_NAME, emptyMap())) {
                    is Outcome.Err -> return r
                    is Outcome.Ok -> r.value
                }
            val lastId = built.steps.lastOrNull()?.id
            val options =
                when (scope) {
                    TrialScope.ALL -> FlowRunOptions()
                    TrialScope.LAST_STEP -> FlowRunOptions(onlyNode = lastId)
                }
            return Outcome.Ok(runDraft(built, options))
        }

        override fun addPicked(
            element: PickedElement,
            kind: PickKind,
        ) {
            if (!stateFlow.value.active) return
            val type = if (kind == PickKind.CLICK) UserActionType.CLICK else UserActionType.WAIT_ELEMENT
            val action =
                UserAction(
                    type = type,
                    packageName = null,
                    className = element.className,
                    viewId = element.viewId,
                    text = element.text,
                    contentDescription = element.contentDescription,
                    bounds = element.bounds,
                    isPassword = false,
                    time = clock.nowMillis(),
                )
            synchronized(lock) { actions += action }
            publish()
        }

        override fun cancel() {
            synchronized(lock) { actions.clear() }
            stopInternal()
        }

        override fun typedInputs(): List<TypedInput> =
            previewDraft()?.typedNodes?.map { (id, text) -> TypedInput(id, text) }.orEmpty()

        override suspend fun polishName(name: String): Outcome<PolishedName> {
            val draft =
                previewDraft()?.definition
                    ?: return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "没有可整理的操作"))
            val steps = draft.steps.map { describe(it) }
            return polisher.polish(name, steps)
        }

        /**
         * 不停止录制，预览当前操作转换成的草稿（只用于读取文字输入与步骤描述）。
         * 屏幕尺寸只影响比例坐标后备，不影响节点 id，这里用固定尺寸。
         */
        private fun previewDraft(): RecordingDraft? {
            val recorded = synchronized(lock) { actions.toList() }
            if (recorded.isEmpty()) return null
            return RecordingConverter.convert(
                flowId = PREVIEW_ID,
                name = PREVIEW_ID,
                actions = recorded,
                screen = ScreenSize(PREVIEW_SCREEN, PREVIEW_SCREEN),
                options =
                    RecordingOptions(
                        settleMinMs = 0,
                        settleMaxMs = 0,
                        maxSteps = configProvider.current()[FlowConfig.RECORDER].maxSteps,
                    ),
            )
        }

        private fun describe(node: Node): String =
            when (node) {
                is ToolNode -> "${node.tool} ${node.target?.value.orEmpty()}".trim()
                else -> node.id
            }

        /** 停止采集，把录到的操作转换为草稿（校验与保存由调用方完成）。 */
        private suspend fun buildDraft(
            name: String,
            paramNames: Map<String, String>,
        ): Outcome<FlowDefinition> {
            if (name.isBlank()) return Outcome.Err(AppError(code = FlowErrorCodes.ARGS_INVALID, message = "流程名称不能为空"))
            val recorded = synchronized(lock) { actions.toList() }
            stopInternal()
            if (recorded.isEmpty()) return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "没有录制到任何操作"))
            val shot =
                when (val r = screens.snapshot()) {
                    is Outcome.Err -> return Outcome.Err(r.error)
                    is Outcome.Ok -> r.value
                }
            val cfg = configProvider.current()
            val draft =
                RecordingConverter.convert(
                    flowId = ids.newId(),
                    name = name.trim(),
                    actions = recorded,
                    screen = ScreenSize(shot.screenWidth, shot.screenHeight),
                    options =
                        RecordingOptions(
                            settleMinMs = cfg[FlowConfig.SAVE].settleMinMs,
                            settleMaxMs = cfg[FlowConfig.SAVE].settleMaxMs,
                            maxSteps = cfg[FlowConfig.RECORDER].maxSteps,
                        ),
                )
            if (draft.definition.steps.isEmpty()) {
                return Outcome.Err(AppError(code = FlowErrorCodes.INVALID, message = "录制的操作无法转换成流程"))
            }
            return Outcome.Ok(RecordingConverter.promoteParams(draft.definition, paramNames))
        }

        /** 不落库运行草稿；一次性运行，运行记录写入普通线程。 */
        private suspend fun runDraft(
            flow: FlowDefinition,
            options: FlowRunOptions,
        ): RunOutcome {
            val runId = RunId(ids.newId())
            val thread = threads.createThread(title = flow.name, parent = null)
            val params = ParamBinder.bind(flow.params, JsonObject(emptyMap()))
            val request =
                FlowRunRequest(
                    runId = runId,
                    threadId = thread,
                    flow = flow,
                    params = params,
                    origin = ORIGIN_TRIAL,
                    options = options,
                    config = configProvider.current(),
                )
            return coroutineScope { session.start(request, this).await().status }
        }

        private fun stopInternal() {
            monitor.stop()
            scope?.cancel()
            scope = null
            stateFlow.value = RecordingState(active = false)
        }

        private fun publish() {
            val shown = configProvider.current()[FlowConfig.RECORDER].recentShown
            val snapshot =
                synchronized(lock) {
                    Triple(
                        actions.size,
                        actions.firstOrNull { it.packageName != null }?.packageName,
                        actions.takeLast(shown).map { RecordedStep(it.type, labelOf(it)) },
                    )
                }
            if (stateFlow.value.active) {
                stateFlow.value =
                    RecordingState(
                        active = true,
                        actionCount = snapshot.first,
                        packageName = snapshot.second,
                        recent = snapshot.third,
                    )
            }
        }

        /** 操作目标的可读标识：文字 > 描述 > id 末段；密码框不展示文字。 */
        private fun labelOf(action: UserAction): String? =
            action.text?.takeIf { it.isNotBlank() }
                ?: action.contentDescription?.takeIf { it.isNotBlank() }
                ?: action.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

        private companion object {
            const val TRIAL_NAME = "录制试运行"
            const val ORIGIN_TRIAL = "record_trial"
            const val PREVIEW_ID = "preview"
            const val PREVIEW_SCREEN = 1000
        }
    }
