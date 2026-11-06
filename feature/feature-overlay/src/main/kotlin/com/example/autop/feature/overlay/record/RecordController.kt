/**
 * 流程录制面板装配（docs/design/flows-recording-recovery.md V5）：圆弧菜单「录制流程」展开面板，
 * 面板控制录制会话（开始/撤销/保存/放弃/拾取元素）。录制会话与拾取端口分别来自 flow-api 与 device-api。
 */
package com.example.autop.feature.overlay.record

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.autop.agent.RunOutcome
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.core.ui.theme.AutopDimens
import com.example.autop.device.ElementPicker
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlaySpec
import com.example.autop.device.PickedElement
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.R
import com.example.autop.feature.overlay.composeOverlayView
import com.example.autop.feature.overlay.panel.PanelRootView
import com.example.autop.flow.PickKind
import com.example.autop.flow.PolishedName
import com.example.autop.flow.RecordingSession
import com.example.autop.flow.TrialScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** 面板贴右边缘的间距（dp）。 */
private const val EDGE_MARGIN_DP: Float = 8f

/** 面板纵向位置：屏幕高度除以该值（屏幕上部，不挡住底部操作区）。 */
private const val TOP_DIVISOR: Int = 4

/** 面板保存/拾取提示。 */
internal enum class RecordNotice { SAVED, FAILED, PICK_FAILED, TRIAL_SUCCEEDED, TRIAL_FAILED, POLISH_FAILED }

/** 录制面板的装配与控制（进程级）。 */
@Singleton
@Suppress("LongParameterList") // 装配类：会话 + 拾取 + 窗口宿主 + 日志 + 调度
public class RecordController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val session: RecordingSession,
        private val targetPick: TargetPickSession,
        private val picker: ElementPicker,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("RecordController"))

        private val visibleState = MutableStateFlow(false)
        private val savingState = MutableStateFlow(false)
        private val noticeState = MutableStateFlow<RecordNotice?>(null)
        private val pendingState = MutableStateFlow<PickedElement?>(null)
        private val trialingState = MutableStateFlow(false)
        private val chosenState = MutableStateFlow<Set<String>>(emptySet())
        private val polishedState = MutableStateFlow<PolishedName?>(null)
        private val expandedState = MutableStateFlow(false)

        /** 展开或收起详情（默认只显示小提示条）。 */
        public fun toggleDetails() {
            expandedState.update { !it }
        }

        /** 切换某个文字输入是否设为参数。 */
        public fun toggleParam(nodeId: String) {
            chosenState.update { if (nodeId in it) it - nodeId else it + nodeId }
        }

        /** AI 整理名称与说明（不改步骤）；结果用于保存。 */
        public fun polish() {
            scope.launch {
                when (val r = session.polishName(context.getString(R.string.record_default_name))) {
                    is Outcome.Ok -> polishedState.value = r.value
                    is Outcome.Err -> noticeState.value = RecordNotice.POLISH_FAILED
                }
            }
        }

        /** 面板是否可见。 */
        public val visible: StateFlow<Boolean> = visibleState.asStateFlow()

        /** 面板工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun recordFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                PanelRootView(
                    windowContext,
                    onOutsideTouch = {},
                    onBack = ::dismiss,
                ).apply {
                    addView(composeOverlayView(windowContext, owner) { RecordContent() })
                }
            }

        /** 拾取层工厂：整屏透明，点击回传坐标。 */
        public fun pickerFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { PickLayer(onPick = ::routePick) }
            }

        /** 展开面板（已显示时不重复 show）。录制会话不随面板收起而结束。 */
        public fun show() {
            if (visibleState.value) return
            visibleState.value = true
            expandedState.value = false
            host.show(OverlayWindowIds.RECORD, recordSpec(context))
        }

        /** 收起面板；正在进行的录制继续，可从圆弧菜单再次打开。 */
        public fun dismiss() {
            if (!visibleState.value) return
            visibleState.value = false
            host.hide(OverlayWindowIds.RECORD)
        }

        /** 开始录制。 */
        public fun start() {
            noticeState.value = null
            session.start()
        }

        /** 撤销上一步。 */
        public fun undo() = session.undo()

        /** 放弃录制。 */
        public fun cancel() {
            noticeState.value = null
            pendingState.value = null
            chosenState.value = emptySet()
            polishedState.value = null
            session.cancel()
        }

        /** 保存为待确认草稿。 */
        public fun save() {
            if (savingState.value) return
            savingState.value = true
            scope.launch {
                val name = polishedState.value?.name ?: context.getString(R.string.record_default_name)
                val chosen = chosenState.value
                val paramNames =
                    session
                        .typedInputs()
                        .filter { it.nodeId in chosen }
                        .mapIndexed { i, input -> input.nodeId to "param${i + 1}" }
                        .toMap()
                noticeState.value =
                    when (val r = session.finish(name, paramNames)) {
                        is Outcome.Ok -> RecordNotice.SAVED
                        is Outcome.Err -> {
                            logger.e("Record", "record_save_failed", "error" to r.error.code)
                            RecordNotice.FAILED
                        }
                    }
                savingState.value = false
            }
        }

        /** 试运行草稿（整个或只最后一步）；结果以提示展示，不保存。 */
        public fun trial(scope: TrialScope) {
            if (trialingState.value) return
            trialingState.value = true
            this.scope.launch {
                noticeState.value =
                    when (val r = session.trialRun(scope)) {
                        is Outcome.Ok ->
                            if (r.value ==
                                RunOutcome.SUCCEEDED
                            ) {
                                RecordNotice.TRIAL_SUCCEEDED
                            } else {
                                RecordNotice.TRIAL_FAILED
                            }
                        is Outcome.Err -> RecordNotice.TRIAL_FAILED
                    }
                trialingState.value = false
            }
        }

        /** 拾取层点击：编辑器在等待重选时交给编辑器，否则用于录制面板。 */
        private fun routePick(
            x: Int,
            y: Int,
        ) {
            if (targetPick.active) targetPick.onPicked(x, y) else onPicked(x, y)
        }

        /** 进入拾取：面板让路，显示整屏拾取层。 */
        public fun startPick() {
            if (!visibleState.value) return
            host.hide(OverlayWindowIds.RECORD)
            host.show(OverlayWindowIds.PICKER, pickerSpec(context))
        }

        /** 拾取层收到点击：查元素，收起拾取层，面板回来显示结果。 */
        public fun onPicked(
            x: Int,
            y: Int,
        ) {
            host.hide(OverlayWindowIds.PICKER)
            scope.launch {
                when (val r = picker.pickAt(x, y)) {
                    is Outcome.Ok -> {
                        pendingState.value = r.value
                        noticeState.value = null
                    }
                    is Outcome.Err -> noticeState.value = RecordNotice.PICK_FAILED
                }
                if (visibleState.value) host.show(OverlayWindowIds.RECORD, recordSpec(context))
            }
        }

        /** 把已拾取的元素加入录制：点击或等待出现。 */
        public fun addPicked(kind: PickKind) {
            val element = pendingState.value ?: return
            session.addPicked(element, kind)
            pendingState.value = null
        }

        /** 丢弃已拾取的元素。 */
        public fun discardPick() {
            pendingState.value = null
        }

        @Composable
        private fun RecordContent() {
            val state by session.state.collectAsState()
            val saving by savingState.collectAsState()
            val notice by noticeState.collectAsState()
            val pending by pendingState.collectAsState()
            val trialing by trialingState.collectAsState()
            val callbacks =
                remember(this) {
                    RecordCallbacks(
                        onStart = ::start,
                        onUndo = ::undo,
                        onSave = ::save,
                        onCancel = ::cancel,
                        onClose = ::dismiss,
                        onToggleDetails = ::toggleDetails,
                        onPick = ::startPick,
                        onAddClick = { addPicked(PickKind.CLICK) },
                        onAddWait = { addPicked(PickKind.WAIT_ELEMENT) },
                        onDiscardPick = ::discardPick,
                        onTrialAll = { trial(TrialScope.ALL) },
                        onTrialLast = { trial(TrialScope.LAST_STEP) },
                        onToggleParam = ::toggleParam,
                        onPolish = ::polish,
                    )
                }
            val inputs = remember(state) { session.typedInputs() }
            val chosen by chosenState.collectAsState()
            val polished by polishedState.collectAsState()
            val expanded by expandedState.collectAsState()
            RecordScreen(
                state =
                    RecordUiState(
                        active = state.active,
                        actionCount = state.actionCount,
                        saving = saving,
                        notice = notice,
                        picked = pending != null,
                        trialing = trialing,
                        inputs = inputs,
                        chosen = chosen,
                        polishedName = polished?.name,
                        expanded = expanded,
                        recent = state.recent,
                    ),
                callbacks = callbacks,
            )
        }
    }

/** 面板 spec：贴右边缘，位于屏幕上部（不挡住底部的操作区）；高度随内容，详情区自己限高并可滑动。 */
private fun recordSpec(context: Context): OverlaySpec {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val width = (AutopDimens.overlayPanelWidth.value * density).roundToInt()
    val margin = (EDGE_MARGIN_DP * density).roundToInt()
    return OverlaySpec(
        x = metrics.widthPixels - width - margin,
        y = metrics.heightPixels / TOP_DIVISOR,
        width = width,
        height = WindowManager.LayoutParams.WRAP_CONTENT,
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
        watchOutsideTouch = false,
    )
}

/** 拾取层 spec：整屏、可触摸，接收点击后回传坐标。 */
internal fun pickerSpec(context: Context): OverlaySpec {
    val metrics = context.resources.displayMetrics
    return OverlaySpec(
        x = 0,
        y = 0,
        width = metrics.widthPixels,
        height = metrics.heightPixels,
        touchable = true,
        focusable = false,
        gravity = OverlayGravity.TOP_LEFT,
        watchOutsideTouch = false,
    )
}
