/**
 * [DebugPage] 的 debug 源集实现（docs/tech/08 §4.6、docs/tech/10 WP4）：
 * 通过 [DebugPageModule] 的 @Provides 注入 `Optional<DebugPage>`（N12：@Provides 必须 public）。
 */
package com.example.autop.feature.settings.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 调试页实现：挂 [DebugViewModel] 与 [DebugScreen]。 */
@Singleton
public class DebugPageImpl
    @Inject
    constructor() : DebugPage {
        @Composable
        override fun Content(onBack: () -> Unit) {
            val viewModel: DebugViewModel = hiltViewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            DebugScreen(state = state, onBack = onBack, onAction = viewModel::onAction)
        }
    }

/** 调试页绑定（仅 debug 源集存在；release 的 `Optional<DebugPage>` 为空）。 */
@Module
@InstallIn(SingletonComponent::class)
public object DebugImplModule {
    /** 提供 [DebugPage] 实现。 */
    @Provides
    @Singleton
    public fun provideDebugPage(impl: DebugPageImpl): DebugPage = impl
}
