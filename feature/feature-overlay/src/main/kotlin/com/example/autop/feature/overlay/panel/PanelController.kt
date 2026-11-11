/**
 * 输入面板窗口装配（docs/tech/08 §5.2，N182）：即时创建任务的输入面板显示/收起与焦点切换。
 * 运行/问题/拦截内容迁入进度悬浮窗（`ProgressController`，N182）。
 * 窗口 `register` 由 app 组装层调用 `OverlayWindowManager`（FEATURE 不依赖 IMPL，docs/tech/01 §4），
 * 本类只通过 [com.example.autop.device.OverlayHost] 端口操作窗口。
 */
package com.example.autop.feature.overlay.panel

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.autop.core.ui.component.OptionItem
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayHost
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.feature.overlay.ball.BallStateHolder
import com.example.autop.feature.overlay.composeOverlayView
import com.example.autop.feature.overlay.progress.ProgressController
import com.example.autop.llm.ModelProfileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 输入面板窗口的注册与收起（进程级）。 */
@Singleton
public class PanelController
    @Inject
    constructor(
        private val modelProfiles: ModelProfileStore,
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val holder: BallStateHolder,
        private val actions: PanelRunActions,
        private val progressController: ProgressController,
    ) {
        private val shownState = MutableStateFlow(false)

        /** 面板窗口内容工厂（app 组装层注册到 `OverlayWindowManager`）。 */
        public fun panelFactory(): (Context, OverlayLifecycleOwner) -> View =
            { windowContext, owner ->
                PanelRootView(
                    windowContext,
                    onOutsideTouch = ::dismiss,
                    onBack = ::dismiss,
                ).apply {
                    addView(composeOverlayView(windowContext, owner) { PanelContent() })
                }
            }

        /** 展开输入面板（圆弧菜单「即时创建任务」，N182）。 */
        public fun showInput(screenWidthPx: Int) {
            shownState.value = true
            host.show(OverlayWindowIds.PANEL, panelSpec(context, holder.position.value, screenWidthPx))
        }

        /** 收起输入面板。 */
        public fun dismiss() {
            if (!shownState.value) return
            shownState.value = false
            host.hide(OverlayWindowIds.PANEL)
        }

        /** 面板内容：发送后收起面板并转到进度悬浮窗（N182 发送交互修复）。 */
        @Composable
        private fun PanelContent() {
            val uiState by holder.uiState.collectAsState()
            val modelOptions by remember(this) {
                modelProfiles.profiles.map { list -> list.map { OptionItem(id = it.id, label = it.displayName) } }
            }.collectAsState(emptyList())
            val callbacks =
                remember(this) {
                    PanelCallbacks(
                        onSend = { text, modelProfileId ->
                            actions.send(text, modelProfileId) {
                                dismiss()
                                progressController.show()
                            }
                        },
                        onDismiss = ::dismiss,
                    )
                }
            PanelScreen(
                callbacks = callbacks,
                modelOptions = modelOptions,
            )
        }
    }
