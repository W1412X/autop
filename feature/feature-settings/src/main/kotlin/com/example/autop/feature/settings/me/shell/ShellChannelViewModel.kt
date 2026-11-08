/**
 * 注入通道子页 ViewModel（N200，docs/tech/08 §4.5 `me/shell`）：
 * 无线调试配对（配对码 SPAKE2）成功后自动连接 shell 注入通道；连接失效时
 * [ShellInput] 自动回落无障碍注入，手势工具不因通道断开而失败。
 */
package com.example.autop.feature.settings.me.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.ShellInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 注入通道子页 ViewModel。 */
@HiltViewModel
public class ShellChannelViewModel
    @Inject
    constructor(
        private val shellInput: ShellInput,
        private val discovery: PairingDiscovery,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val portText = MutableStateFlow("")

        private val codeText = MutableStateFlow("")

        private val busy = MutableStateFlow(false)

        private val notice = MutableStateFlow<ShellNotice?>(null)

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

        public val uiState: StateFlow<ShellChannelUiState> =
            combine(
                combine(shellInput.state, shellInput.paired, discovery.discoveredPort) { state, paired, port ->
                    Triple(state, paired, port)
                },
                portText,
                codeText,
                busy,
                notice,
            ) { channel, port, code, running, hint ->
                ShellChannelUiState(
                    shellState = channel.first,
                    portText = port,
                    codeText = code,
                    busy = running,
                    discovering = !channel.second && channel.third == null,
                    notice = hint,
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
    }
