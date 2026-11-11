/**
 * 悬浮窗附着登记（仅主线程访问）：内容工厂、显示记录与隐藏状态的窗口操作。
 * 通道由 [OverlayChannelResolver] 决定后传入，本类只做窗口生命周期管理。
 */
package com.example.autop.device.impl.overlay

import android.content.Context
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import com.example.autop.core.common.Logger
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.InjectionFootprint
import com.example.autop.device.OverlayGravity
import com.example.autop.device.OverlaySpec
import com.example.autop.device.impl.ServiceHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 注入让路的固定安全边距（dp）：吸收坐标误差与窗口贴边抖动（N222）。 */
private const val OCCLUSION_MARGIN_DP = 8f

/** 窗口附着登记表：[OverlayWindowManager] 的窗口操作实现。 */
@Singleton
@Suppress("TooManyFunctions") // 窗口生命周期操作面（注册/显示/更新/隐藏/重建/隐藏态/占位判定）职责单一
public class OverlayWindowRegistry
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val serviceHolder: ServiceHolder,
        private val logger: Logger,
    ) {
        /** 内容工厂（app 组装时注册 feature-overlay 的工厂；owner 供 Compose 层注入）。 */
        private val factories = HashMap<String, (Context, OverlayLifecycleOwner) -> View>()

        /** 显示中的窗口记录。 */
        private val entries = LinkedHashMap<String, Entry>()

        /** 截图隐藏期间为 true（此间新附着的窗口保持隐藏）。 */
        private var hidden = false

        /** 进行中的注入占位（可嵌套；同一窗口被任一占位命中即让路，N222）。 */
        private val footprints = mutableListOf<InjectionFootprint>()

        /** 注册内容工厂；该 id 正在显示时用新工厂重建内容。 */
        public fun register(
            id: String,
            factory: (Context, OverlayLifecycleOwner) -> View,
            channel: OverlayChannel?,
        ) {
            factories[id] = factory
            val entry = entries[id] ?: return
            detach(entry)
            if (channel != null) attach(id, channel)
        }

        /** 显示（或更新）窗口；channel 为 null 时不附着（能力缺失）。 */
        public fun show(
            id: String,
            spec: OverlaySpec,
            channel: OverlayChannel?,
        ) {
            val entry = entries.getOrPut(id) { Entry(spec) }
            entry.spec = spec
            detach(entry)
            if (channel != null) attach(id, channel)
        }

        /**
         * 更新窗口布局。
         *
         * 窗口已附着时不能再改 `type`（系统抛 `IllegalArgumentException:
         * Window type can not be changed after the window is added`）：通道切换与
         * [update] 并发时（[OverlayWindowManager] 的通道收集与工具更新可能先后到达），
         * 必须先在旧通道移除，再按新通道重新附着；通道相同时以附着时的通道构建参数。
         */
        public fun update(
            id: String,
            spec: OverlaySpec,
            channel: OverlayChannel?,
        ) {
            val entry = entries[id] ?: return
            entry.spec = spec
            // spec 变更新几何：按新 spec 重算是否与进行中的注入占位冲突（N222）。
            entry.occluded = occludes(entry, preferView = false)
            val window = entry.window
            if (window == null || channel == null) return
            if (window.channel != channel) {
                detach(entry)
                attach(id, channel)
                return
            }
            window.windowManager.updateViewLayout(
                window.view,
                buildOverlayParams(window.channel, spec, hidden, entry.occluded),
            )
        }

        /** 隐藏并移除窗口。 */
        public fun hide(id: String) {
            val entry = entries.remove(id) ?: return
            detach(entry)
        }

        /** 通道变化后在新通道重建全部窗口（位置保留）。 */
        public fun rebuildAll(channel: OverlayChannel?) {
            for ((id, entry) in entries) {
                detach(entry)
                if (channel != null) attach(id, channel)
            }
        }

        /** 显示中、未隐藏且可触摸的窗口规格（触摸探针排除自家窗口用，questions.md N139）。 */
        public fun touchableSpecs(): List<OverlaySpec> = touchableEntries().map { (_, entry) -> entry.spec }

        /** 截图隐藏：全部窗口透明且不可触摸（docs/tech/05 §10）。 */
        public fun setHidden(value: Boolean) {
            if (hidden == value) return
            hidden = value
            for (entry in entries.values) {
                val window = entry.window ?: continue
                window.windowManager.updateViewLayout(
                    window.view,
                    buildOverlayParams(window.channel, entry.spec, value, entry.occluded),
                )
            }
        }

        /**
         * 登记一次注入占位（N222）：只让与占位冲突的可触摸窗口让路（透明 + 不可触摸不可聚焦），
         * 其余窗口保持原样。注入期间新附着的窗口同样按占位判定（对齐 [setHidden] 的继承语义）。
         */
        public fun addFootprint(footprint: InjectionFootprint) {
            footprints += footprint
            applyOcclusion()
        }

        /** 解除一次注入占位；仅恢复不再被任何占位命中的窗口。 */
        public fun removeFootprint(footprint: InjectionFootprint) {
            footprints.remove(footprint)
            applyOcclusion()
        }

        /** 按当前占位重算全部窗口（等待重排后仍冲突时补一次，N222 自检重试）。 */
        public fun refreshOcclusion() {
            applyOcclusion()
        }

        /** 仍在让路的窗口 id（日志留痕用）。 */
        public fun occludingIds(): List<String> = entries.filterValues { it.occluded }.keys.toList()

        /** 仍在让路的窗口与矩形（`id[l,t,r,b]`，日志留痕用）。 */
        public fun occludingRects(): List<String> =
            entries.filterValues { it.occluded }.map { (id, entry) -> rectLabel(id, entry) }

        /** 自检：占位生效后仍可能收到本次注入的可触摸窗口（`id[l,t,r,b]`；应为空）。 */
        public fun conflictingTouchableWindows(): List<String> =
            entries.entries
                .filter { (_, entry) -> !hidden && entry.receivesInput() && occludes(entry, preferView = true) }
                .map { (id, entry) -> rectLabel(id, entry) }

        /** 仍可触摸的自家窗口 id（不含已让路的窗口；注入期应为空）。 */
        public fun touchableWindowIds(): List<String> = touchableEntries().map { (id, _) -> id }

        /** 显示中、未隐藏、未让路且可触摸的窗口条目。 */
        private fun touchableEntries(): List<Map.Entry<String, Entry>> =
            entries.entries.filter { (_, entry) -> !hidden && entry.receivesInput() }

        /** 按当前全部占位重算并只重排真正变化的窗口。 */
        private fun applyOcclusion() {
            for (entry in entries.values) {
                val next = occludes(entry, preferView = true)
                if (next == entry.occluded) continue
                entry.occluded = next
                entry.window?.let { window ->
                    window.windowManager.updateViewLayout(
                        window.view,
                        buildOverlayParams(window.channel, entry.spec, hidden, next),
                    )
                }
            }
        }

        /** 窗口是否与任一进行中的注入占位冲突（不可触摸的窗口无需让路）。 */
        private fun occludes(
            entry: Entry,
            preferView: Boolean,
        ): Boolean {
            if (footprints.isEmpty() || !entry.spec.touchable) return false
            return footprints.any { footprint ->
                when (footprint) {
                    InjectionFootprint.All -> true
                    InjectionFootprint.Focus -> entry.spec.focusable
                    is InjectionFootprint.TouchPoint -> rectOf(entry, preferView).contains(footprint)
                }
            }
        }

        /**
         * 窗口当前屏幕矩形：优先取已附着视图的真实位置与尺寸（含系统栏内缩等布局差异），
         * 视图尚未布局或按 spec 更新几何时退回 spec 推导（gravity + 屏幕尺寸一致）。
         */
        private fun rectOf(
            entry: Entry,
            preferView: Boolean,
        ): RectF {
            val view =
                if (preferView) {
                    entry.window?.view?.takeIf { it.width > 0 && it.height > 0 }
                } else {
                    null
                }
            if (view != null) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return RectF(
                    location[0].toFloat(),
                    location[1].toFloat(),
                    (location[0] + view.width).toFloat(),
                    (location[1] + view.height).toFloat(),
                )
            }
            return specRectOf(entry.spec)
        }

        /** spec → 屏幕矩形（与 [buildOverlayParams] 的 gravity 语义一致）。 */
        private fun specRectOf(spec: OverlaySpec): RectF {
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val width = if (spec.width <= 0) screenWidth else spec.width
            val height = if (spec.height <= 0) screenHeight else spec.height
            val left =
                when (spec.gravity) {
                    OverlayGravity.TOP_LEFT, OverlayGravity.LEFT, OverlayGravity.BOTTOM_LEFT -> spec.x
                    OverlayGravity.TOP, OverlayGravity.CENTER, OverlayGravity.BOTTOM ->
                        (screenWidth - width) / 2 + spec.x
                    OverlayGravity.TOP_RIGHT, OverlayGravity.RIGHT, OverlayGravity.BOTTOM_RIGHT ->
                        screenWidth - width - spec.x
                }
            val top =
                when (spec.gravity) {
                    OverlayGravity.TOP_LEFT, OverlayGravity.TOP, OverlayGravity.TOP_RIGHT -> spec.y
                    OverlayGravity.LEFT, OverlayGravity.CENTER, OverlayGravity.RIGHT ->
                        (screenHeight - height) / 2 + spec.y
                    OverlayGravity.BOTTOM_LEFT, OverlayGravity.BOTTOM, OverlayGravity.BOTTOM_RIGHT ->
                        screenHeight - height - spec.y
                }
            return RectF(
                left.toFloat(),
                top.toFloat(),
                (left + width).toFloat(),
                (top + height).toFloat(),
            )
        }

        /** 落点命中判定：固定安全边距 + 调用方容差（N222）。 */
        private fun RectF.contains(point: InjectionFootprint.TouchPoint): Boolean {
            val margin =
                point.marginPx +
                    (OCCLUSION_MARGIN_DP * context.resources.displayMetrics.density).toInt()
            return point.x >= left - margin &&
                point.x <= right + margin &&
                point.y >= top - margin &&
                point.y <= bottom + margin
        }

        private fun rectLabel(
            id: String,
            entry: Entry,
        ): String {
            val rect = rectOf(entry, preferView = true)
            return "$id[${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}]"
        }

        private fun attach(
            id: String,
            channel: OverlayChannel,
        ) {
            val entry = entries[id]
            val factory = factories[id]
            val windowManager = windowManagerOf(channel)
            if (entry == null || factory == null || windowManager == null) {
                logger.w(TAG, "overlay_attach_skipped", "id" to id, "channel" to channel.name)
                return
            }
            // 注入期间新附着的窗口：附着参数即按占位判定，避免先闪现再让路（N222）。
            entry.occluded = occludes(entry, preferView = false)
            val owner = OverlayLifecycleOwner()
            val view = factory(contextFor(channel), owner)
            val params = buildOverlayParams(channel, entry.spec, hidden, entry.occluded)
            val added = runCatching { windowManager.addView(view, params) }.isSuccess
            if (!added) {
                logger.w(TAG, "overlay_add_failed", "id" to id, "channel" to channel.name)
                return
            }
            owner.onWindowAttached()
            entry.window = AttachedWindow(view, owner, windowManager, channel)
        }

        private fun detach(entry: Entry) {
            val window = entry.window ?: return
            runCatching { window.windowManager.removeView(window.view) }
            window.owner.onWindowDetached()
            entry.window = null
        }

        private fun windowManagerOf(channel: OverlayChannel): WindowManager? =
            when (channel) {
                OverlayChannel.ACCESSIBILITY -> serviceHolder.current()?.getSystemService(WindowManager::class.java)
                OverlayChannel.APPLICATION -> context.getSystemService(WindowManager::class.java)
            }

        private fun contextFor(channel: OverlayChannel): Context =
            when (channel) {
                OverlayChannel.ACCESSIBILITY -> serviceHolder.current() ?: context
                OverlayChannel.APPLICATION -> context
            }

        private companion object {
            const val TAG = "OverlayWindowRegistry"
        }
    }

/** 显示中的窗口条目：spec 可被 update 替换。 */
private class Entry(
    var spec: OverlaySpec,
    /** 注入让路中的窗口（透明 + 不可触摸不可聚焦），注入结束按占位重算恢复（N222）。 */
    var occluded: Boolean = false,
) {
    var window: AttachedWindow? = null

    /** 显示中、未让路且按 spec 可触摸——只有这些窗口可能收到输入。 */
    fun receivesInput(): Boolean = window != null && spec.touchable && !occluded
}

/** 已附着到 WindowManager 的窗口。 */
private class AttachedWindow(
    val view: View,
    val owner: OverlayLifecycleOwner,
    val windowManager: WindowManager,
    val channel: OverlayChannel,
)
