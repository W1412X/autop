/**
 * 注入通道子页 ViewModel（N200，docs/tech/08 §4.5 `me/shell`）：
 * 无线调试配对成功后自动连接 shell 注入通道；连接失效时 [ShellInput] 自动回落无障碍注入，手势工具不因通道断开而失败。
 *
 * Shell 执行总闸（shell-exec 设计 §2.5）：首次开启需要知情同意；关闭时立即回收所有宿主与命令。
 */
package com.example.autop.feature.settings.me.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.FeaturesConfig
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.ShellExec
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** 注入通道子页 ViewModel。 */
@HiltViewModel
public class ShellChannelViewModel
    @Inject
    constructor(
        private val shellInput: ShellInput,
        private val discovery: PairingDiscovery,
        private val dispatchers: DispatcherProvider,
        private val configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val shellExec: ShellExec,
    ) : ViewModel() {
        private val portText = MutableStateFlow("")
        private val codeText = MutableStateFlow("")
        private val busy = MutableStateFlow(false)
        private val notice = MutableStateFlow<ShellNotice?>(null)
        private val consentPending = MutableStateFlow(false)

        init {
            // 自动发现端口回填：端口为空且非忙碌时填入（用户手输优先，不覆盖）。
            viewModelScope.launch(dispatchers.default) {
                discovery.discoveredPort.collect { port ->
                    if (port != null && portText.value.isEmpty() && !busy.value) {
                        portText.update { port.toString() }
                    }
                }
            }
        }

        private val shellExecEnabled: StateFlow<Boolean> =
            configProvider
                .observe()
                .map { it[FeaturesConfig.FEATURES].shellExec }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val channelFlow: StateFlow<ChannelSnapshot> =
            combine(
                shellInput.state,
                shellInput.paired,
                discovery.discoveredPort,
                shellExecEnabled,
            ) { state, paired, port, enabled ->
                ChannelSnapshot(state, paired, port, enabled)
            }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ChannelSnapshot(ShellState.Disconnected, false, null, false),
            )

        private val formFlow: StateFlow<FormSnapshot> =
            combine(portText, codeText, busy, notice, consentPending) { port, code, running, hint, consent ->
                FormSnapshot(port, code, running, hint, consent)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, FormSnapshot("", "", false, null, false))

        public val uiState: StateFlow<ShellChannelUiState> =
            combine(channelFlow, formFlow) { channel, form ->
                ShellChannelUiState(
                    shellState = channel.state,
                    portText = form.port,
                    codeText = form.code,
                    busy = form.busy,
                    discovering = !channel.paired && channel.port == null,
                    notice = form.notice,
                    shellExecEnabled = channel.shellExecEnabled,
                    shellExecConsentPending = form.consent,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ShellChannelUiState(),
            )

        public fun onAction(action: ShellChannelAction) {
            when (action) {
                is ShellChannelAction.UpdatePort -> portText.update { action.text }
                is ShellChannelAction.UpdateCode -> codeText.update { action.text }
                ShellChannelAction.Pair -> pair()
                ShellChannelAction.Connect -> connect()
                is ShellChannelAction.SetShellExec -> setShellExec(action.enabled)
                ShellChannelAction.ConfirmShellExecConsent -> confirmConsent()
                ShellChannelAction.DismissShellExecConsent -> consentPending.value = false
            }
        }

        /** 开启需要先知情同意；关闭直接写入并回收所有宿主。 */
        private fun setShellExec(enabled: Boolean) {
            if (enabled) {
                consentPending.value = true
            } else {
                writeShellExec(false)
                viewModelScope.launch(dispatchers.io) { shellExec.closeAll() }
            }
        }

        private fun confirmConsent() {
            consentPending.value = false
            writeShellExec(true)
        }

        private fun writeShellExec(enabled: Boolean) {
            viewModelScope.launch(dispatchers.io) {
                configEditor.setOverride(FeaturesConfig.SHELL_EXEC_PATH, JsonPrimitive(enabled))
            }
        }

        /** 无线调试配对（N200）：配对成功后自动连接注入通道。 */
        private fun pair() {
            val port = portText.value.trim().toIntOrNull()
            if (port == null || codeText.value.isBlank()) {
                notice.update { ShellNotice.InputMissing }
                return
            }
            if (busy.value) return
            busy.value = true
            notice.value = null
            viewModelScope.launch(dispatchers.io) {
                val result = shellInput.pair(port, codeText.value.trim())
                if (result is Outcome.Ok) shellInput.connect()
                if (result is Outcome.Err) {
                    notice.update { ShellNotice.PairFailed(result.error.message) }
                }
                busy.value = false
            }
        }

        /** 手动连接注入通道（无线调试已开启时）。 */
        private fun connect() {
            if (busy.value) return
            busy.value = true
            notice.value = null
            viewModelScope.launch(dispatchers.io) {
                shellInput.connect()
                busy.value = false
            }
        }

        private data class FormSnapshot(
            val port: String,
            val code: String,
            val busy: Boolean,
            val notice: ShellNotice?,
            val consent: Boolean,
        )

        private data class ChannelSnapshot(
            val state: ShellState,
            val paired: Boolean,
            val port: Int?,
            val shellExecEnabled: Boolean,
        )
    }
