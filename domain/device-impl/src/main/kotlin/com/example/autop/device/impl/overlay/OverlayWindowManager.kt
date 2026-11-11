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
import com.example.autop.device.InjectionFootprint
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlaySpec
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
         * 自家浮层按注入占位让路（N222）：只有与本次动作冲突的可触摸窗口变透明且不可触摸
         * （手势看落点是否在窗口矩形内、按键看窗口是否可聚焦），不冲突的窗口保持原样。
         * 提交参数后等重排落地；落地后仍冲突（新窗口恰好附着等）则重算一次再等一帧。
         * 返回是否自检通过（没有仍可能吞掉本次注入的自家窗口）。
         */
        public suspend fun occlude(
            footprint: InjectionFootprint,
            timeoutMs: Long,
        ): Boolean {
            withContext(main) { registry.addFootprint(footprint) }
            val firstLanded = awaitRelayoutApplied(timeoutMs)
            if (firstLanded && hasNoConflict()) return true
            withContext(main) { registry.refreshOcclusion() }
            val retryLanded = awaitRelayoutApplied(timeoutMs)
            return retryLanded && hasNoConflict()
        }

        /** 解除一次注入占位（各窗口按剩余占位重算恢复；允许异步落地）。 */
        public fun restoreOcclusion(footprint: InjectionFootprint) {
            scope.launch { registry.removeFootprint(footprint) }
        }

        private suspend fun hasNoConflict(): Boolean =
            withContext(main) { registry.conflictingTouchableWindows().isEmpty() }

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

        /** 仍可触摸的自家窗口 id（注入自检留痕用；注入期应为空）。 */
        public fun touchableWindowIds(): List<String> = registry.touchableWindowIds()

        /** 仍在让路的窗口与矩形（`id[l,t,r,b]`，注入留痕用；主线程求值）。 */
        public suspend fun occludingRects(): List<String> = withContext(main) { registry.occludingRects() }

        /** 仍可能收到本次注入的可触摸窗口（`id[l,t,r,b]`，自检用；主线程求值）。 */
        public suspend fun conflictingTouchableWindows(): List<String> =
            withContext(main) { registry.conflictingTouchableWindows() }

        override suspend fun <T> hiddenDuring(block: suspend () -> T): T {
            withContext(main) { registry.setHidden(true) }
            try {
                return block()
            } finally {
                withContext(main) { registry.setHidden(false) }
            }
        }
    }
