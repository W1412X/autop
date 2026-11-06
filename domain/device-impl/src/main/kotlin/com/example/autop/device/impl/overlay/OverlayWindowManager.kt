/**
 * 悬浮窗宿主（docs/tech/05 §10）：只管理窗口（位置、大小、触摸标志、可见性），
 * 不认识窗口内容——内容由 app 组装时注册的 View 工厂提供（feature-overlay 的 ComposeView）。
 * 双通道（ADR-0004）：无障碍窗口优先、普通悬浮窗备用；服务连接 / 权限 / 配置变化时
 * 在旧通道移除、在新通道重建（位置保留）。
 */
package com.example.autop.device.impl.overlay

import android.content.Context
import android.view.Choreographer
import android.view.View
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlaySpec
import com.example.autop.device.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 布局变更落地要等的帧数：变更所在的那一帧过去即生效（N214）。 */
private const val RELAYOUT_FRAME_WAIT = 2

/** 日志 tag。 */
private const val TAG = "OverlayWindowManager"

/** [OverlayHost] 实现：通道决策在 [OverlayChannelResolver]，窗口操作在 [OverlayWindowRegistry]。 */
@Singleton
public class OverlayWindowManager
    @Inject
    constructor(
        private val resolver: OverlayChannelResolver,
        private val registry: OverlayWindowRegistry,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) : OverlayHost {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("OverlayWindowManager"))
        private val main = dispatchers.main

        init {
            // 服务连接、悬浮窗权限、通道配置变化时重建窗口（docs/tech/05 §10）。
            scope.launch {
                resolver.channelChanges().collect { channel -> registry.rebuildAll(channel) }
            }
        }

        /** 注册窗口内容工厂（app 组装：id → feature-overlay 的 ComposeView 工厂）。 */
        public fun register(
            id: String,
            factory: (Context, OverlayLifecycleOwner) -> View,
        ) {
            scope.launch { registry.register(id, factory, resolver.current()) }
        }

        override fun show(
            id: String,
            spec: OverlaySpec,
        ) {
            scope.launch { registry.show(id, spec, resolver.current()) }
        }

        override fun update(
            id: String,
            spec: OverlaySpec,
        ) {
            scope.launch { registry.update(id, spec, resolver.current()) }
        }

        /**
         * 更新窗口布局；spec 在主线程执行时求值（N191）：调用方的实时状态在落地时取值，
         * 过期的排队任务不会用旧值覆盖新状态（触摸盾放行/恢复的乱序防护）。
         */
        public fun updateLazy(
            id: String,
            specOf: () -> OverlaySpec,
        ) {
            scope.launch { registry.update(id, specOf(), resolver.current()) }
        }

        /** 更新窗口布局并等待落地（N191：触摸盾放行必须先于注入手势生效）。 */
        public suspend fun updateNow(
            id: String,
            specOf: () -> OverlaySpec,
        ) {
            withContext(main) { registry.update(id, specOf(), resolver.current()) }
        }

        /**
         * 等窗口布局变更在系统里真正生效（docs/tech/05 §8.1，N214）。
         *
         * `FLAG_NOT_TOUCHABLE` 的切换要等 WMS 下一次 relayout 重算 touchable region
         * 并同步给 InputDispatcher 才生效，落地前注入的触摸仍会被触摸盾吞掉——只提交
         * 参数（`updateNow`）不够。等两帧即可覆盖“变更落在下一帧 relayout”的情形。
         * 超时返回 false（按已提交继续，不阻塞工具链）。
         */
        public suspend fun awaitRelayoutApplied(timeoutMs: Long): Boolean =
            withContext(main) {
                val landed = withTimeoutOrNull(timeoutMs) { awaitFrames(RELAYOUT_FRAME_WAIT) } != null
                if (!landed) logger.w(TAG, "overlay_relayout_timeout", "timeoutMs" to timeoutMs)
                landed
            }

        /**
         * 封锁自家浮层输入（注入期间用，N216）：期间悬浮球 / 进度窗 / 圆弧菜单 / 面板 /
         * 工具测试面板一律不可触摸（保持可见，不闪烁），避免它们把注入的点击吞掉
         * （实测进度窗遮挡导致点击不生效）。返回是否等到重排落地。
         */
        public suspend fun blockInput(
            target: Rect?,
            timeoutMs: Long,
        ): Boolean {
            withContext(main) { registry.setInputBlocked(true, target) }
            return awaitRelayoutApplied(timeoutMs)
        }

        /** 解除自家浮层输入封锁（各窗口按自身 spec 恢复可触摸）。 */
        public fun unblockInput() {
            scope.launch { registry.setInputBlocked(false, null) }
        }

        /** 等 [count] 帧（Choreographer 每帧回调一次）。 */
        private suspend fun awaitFrames(count: Int) {
            repeat(count) {
                suspendCancellableCoroutine<Unit> { cont ->
                    Choreographer.getInstance().postFrameCallback { _ -> if (cont.isActive) cont.resume(Unit) }
                }
            }
        }

        override fun hide(id: String) {
            scope.launch { registry.hide(id) }
        }

        /** 触摸探针排除自家窗口用：显示中的 touchable 窗口规格（docs/tech/05 §8、questions.md N139）。 */
        public fun touchableSpecs(): List<OverlaySpec> = registry.touchableSpecs()

        override suspend fun <T> hiddenDuring(block: suspend () -> T): T {
            withContext(main) { registry.setHidden(true) }
            try {
                return block()
            } finally {
                withContext(main) { registry.setHidden(false) }
            }
        }
    }
