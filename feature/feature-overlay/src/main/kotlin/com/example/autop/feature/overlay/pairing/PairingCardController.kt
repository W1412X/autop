/**
 * 配对卡悬浮窗装配（P1x 体验改造）：mDNS 发现「使用配对码配对设备」窗口（端口自动就绪）
 * → 直接在系统弹窗上层浮出配对卡，输 6 位码即配对、全程零跳转；配对成功自动收卡停监听。
 * 通知仅作辅助出口（有通知权限才弹）；不依赖任何额外权限。
 */
package com.example.autop.feature.overlay.pairing

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlaySpec
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.ShellInput
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.composeOverlayView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** 配对卡提示（文案在 feature-overlay strings，控制器不接触资源）。 */
internal sealed interface PairingNotice {
    /** 配对输入不完整。 */
    data object Missing : PairingNotice

    /** 配对失败（[message] 为底层错误描述）。 */
    data class Failed(
        val message: String,
    ) : PairingNotice
}

/** 配对卡状态。 */
internal data class PairingCardUiState(
    /** 发现的配对端口（null=未发现，卡片不弹）。 */
    val port: Int? = null,
    val code: String = "",
    val busy: Boolean = false,
    val notice: PairingNotice? = null,
)

/** 配对卡动作回调。 */
internal class PairingCardCallbacks(
    val onCode: (String) -> Unit,
    val onPair: () -> Unit,
    val onRefresh: () -> Unit,
    val onClose: () -> Unit,
)

/** 配对卡悬浮窗控制器（进程级；发现即弹、配对即收）。 */
@Singleton
public class PairingCardController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val discovery: PairingDiscovery,
        private val shellInput: ShellInput,
        dispatchers: DispatcherProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        private val codeText = MutableStateFlow("")
        private val busy = MutableStateFlow(false)
        private val notice = MutableStateFlow<PairingNotice?>(null)
        private var dismissedPort: Int? = null

        /** 卡片状态（端口来自发现登记；用户主动关闭后同端口不再打扰）。 */
        private val uiState: StateFlow<PairingCardUiState> =
            combine(discovery.discoveredPort, codeText, busy, notice) { port, code, running, hint ->
                PairingCardUiState(
                    port = port?.takeIf { it != dismissedPort },
                    code = code,
                    busy = running,
                    notice = hint,
                )
            }.stateIn(scope, SharingStarted.Eagerly, PairingCardUiState())

        /** 配对卡窗口内容工厂（app 组装层注册到 OverlayWindowManager）。 */
        public fun cardFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                composeOverlayView(windowContext, owner) { CardContent() }
            }

        /** 启动监听：发现到可配对端口即弹卡，配对成功/端口消失即收。 */
        public fun start() {
            scope.launch {
                uiState.collect { state ->
                    if (state.port != null && !shellInput.paired.value) {
                        host.show(OverlayWindowIds.PAIRING, pairingCardSpec(context))
                    } else {
                        host.hide(OverlayWindowIds.PAIRING)
                    }
                }
            }
        }

        private fun onCode(text: String) {
            codeText.update { text }
            notice.value = null
        }

        private fun onPair() {
            val port = uiState.value.port ?: return
            val code = codeText.value.trim()
            if (code.isEmpty()) {
                notice.value = PairingNotice.Missing
                return
            }
            if (busy.value) return
            busy.value = true
            notice.value = null
            scope.launch {
                val result = shellInput.pair(port, code)
                if (result is Outcome.Ok) {
                    shellInput.connect()
                } else if (result is Outcome.Err) {
                    notice.update { PairingNotice.Failed(result.error.message) }
                }
                busy.value = false
            }
        }

        private fun onRefresh() {
            notice.value = null
            discovery.refresh()
        }

        private fun onClose() {
            dismissedPort = uiState.value.port
            host.hide(OverlayWindowIds.PAIRING)
        }

        /** 卡片内容：状态接到 [PairingCardScreen]。 */
        @Composable
        private fun CardContent() {
            val state by uiState.collectAsState()
            val callbacks =
                PairingCardCallbacks(
                    onCode = ::onCode,
                    onPair = ::onPair,
                    onRefresh = ::onRefresh,
                    onClose = ::onClose,
                )
            PairingCardScreen(state = state, callbacks = callbacks)
        }
    }

/** 配对卡窗口 spec：顶部居中小卡，可聚焦供输入法（同 PANEL 口径）。 */
internal fun pairingCardSpec(context: Context): OverlaySpec {
    val density = context.resources.displayMetrics.density
    return OverlaySpec(
        x = 0,
        y = (CARD_TOP_DP * density).roundToInt(),
        width = (CARD_WIDTH_DP * density).roundToInt(),
        height = (CARD_HEIGHT_DP * density).roundToInt(),
        touchable = true,
        focusable = true,
        gravity = OverlayGravity.TOP,
    )
}

private const val CARD_TOP_DP: Int = 96
private const val CARD_WIDTH_DP: Int = 320
private const val CARD_HEIGHT_DP: Int = 240
