/**
 * 编辑器的目标重选（flows-recording-recovery.md V7）：显示整屏拾取层，用户点选后把元素转成 [Target] 返回给编辑器。
 * 与录制面板共用同一个拾取层窗口；拾取层的点击由 [RecordController] 按当前是否在重选路由过来。
 */
package com.example.autop.feature.overlay.record

import android.content.Context
import com.example.autop.core.common.AppError
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.exceptionHandler
import com.example.autop.device.ElementPicker
import com.example.autop.device.OverlayHost
import com.example.autop.device.PickedElement
import com.example.autop.feature.overlay.OverlayWindowIds
import com.example.autop.flow.MatchMode
import com.example.autop.flow.Target
import com.example.autop.flow.TargetBy
import com.example.autop.flow.TargetFallback
import com.example.autop.flow.TargetPicker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 目标重选实现。 */
@Singleton
public class TargetPickSession
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val host: OverlayHost,
        private val picker: ElementPicker,
        dispatchers: DispatcherProvider,
        logger: Logger,
    ) : TargetPicker {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("TargetPickSession"))
        private var pending: CompletableDeferred<Outcome<Target>>? = null

        /** 是否正在等待点选（拾取层的点击应路由到这里）。 */
        public val active: Boolean
            get() = pending != null

        override suspend fun pickTarget(): Outcome<Target> {
            if (pending != null) return Outcome.Err(AppError(code = PICK_BUSY, message = "已经在拾取元素"))
            val deferred = CompletableDeferred<Outcome<Target>>()
            pending = deferred
            host.show(OverlayWindowIds.PICKER, pickerSpec(context))
            return deferred.await()
        }

        /** 拾取层收到点击：查元素、收起拾取层、把结果交给等待中的编辑器。 */
        public fun onPicked(
            x: Int,
            y: Int,
        ) {
            val deferred = pending ?: return
            pending = null
            host.hide(OverlayWindowIds.PICKER)
            scope.launch {
                deferred.complete(
                    when (val r = picker.pickAt(x, y)) {
                        is Outcome.Ok -> Outcome.Ok(toTarget(r.value))
                        is Outcome.Err -> r
                    },
                )
            }
        }

        /** 把拾取到的元素转成节点目标：文字 > 描述 > 比例坐标后备（与录制一致）。 */
        private fun toTarget(e: PickedElement): Target {
            val fallback = fallbackOf(e)
            val text = e.text?.takeIf { it.isNotBlank() }
            val desc = e.contentDescription?.takeIf { it.isNotBlank() }
            return when {
                text != null -> Target(by = TargetBy.TEXT, value = text, match = MatchMode.EQUALS, fallback = fallback)
                desc != null -> Target(by = TargetBy.DESC, value = desc, match = MatchMode.EQUALS, fallback = fallback)
                else -> Target(by = TargetBy.TEXT, value = null, fallback = fallback)
            }
        }

        private fun fallbackOf(e: PickedElement): TargetFallback {
            val metrics = context.resources.displayMetrics
            val b = e.bounds
            val x = ((b.l + b.r) / 2) * PERMILLE / metrics.widthPixels
            val y = ((b.t + b.b) / 2) * PERMILLE / metrics.heightPixels
            return TargetFallback(at = listOf(x, y))
        }

        private companion object {
            const val PICK_BUSY = "flow.pick_busy"
            const val PERMILLE = 1000
        }
    }
