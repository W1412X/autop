/**
 * 隐私政策与使用协议的同意状态（ADR-0033）。
 * 同意后写入 `ui.onboarding.agreedVersion`；协议版本变化时需要重新同意。
 */
package com.example.autop.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.onErr
import com.example.autop.core.config.ConfigEditor
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.UiConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** 同意状态；配置读取前为 null（页面先不显示任何内容）。 */
public data class AgreementUiState(
    val agreed: Boolean,
    /** 同意前引导已完成（老用户升级后只需再同意一次，不需要再走引导）。 */
    val completedBefore: Boolean,
)

@HiltViewModel
public class AgreementViewModel
    @Inject
    constructor(
        configProvider: ConfigProvider,
        private val configEditor: ConfigEditor,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : ViewModel() {
        public val uiState: StateFlow<AgreementUiState?> =
            configProvider
                .observe()
                .map { snapshot ->
                    val section = snapshot[UiConfig.ONBOARDING]
                    AgreementUiState(
                        agreed = section.agreedVersion >= UiConfig.LEGAL_AGREEMENT_VERSION,
                        completedBefore = section.completed,
                    )
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** 用户在协议页点击「同意并继续」。 */
        public fun accept() {
            viewModelScope.launch(dispatchers.io) {
                configEditor
                    .setOverride(AGREED_PATH, JsonPrimitive(UiConfig.LEGAL_AGREEMENT_VERSION))
                    .onErr { error ->
                        logger.e(
                            tag = TAG,
                            event = "agreement_save_failed",
                            "code" to error.code,
                            error = error.cause,
                        )
                    }
            }
        }

        private companion object {
            const val TAG = "AgreementViewModel"
            const val AGREED_PATH = "ui.onboarding.agreedVersion"
        }
    }
