/**
 * 悬浮窗附着登记（仅主线程访问）：内容工厂、显示记录与隐藏状态的窗口操作。
 * 通道由 [OverlayChannelResolver] 决定后传入，本类只做窗口生命周期管理。
 */
package com.example.autop.device.impl.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import com.example.autop.core.common.Logger
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.example.autop.device.OverlaySpec
import com.example.autop.device.impl.ServiceHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 窗口附着登记表：[OverlayWindowManager] 的窗口操作实现。 */
@Singleton
@Suppress("TooManyFunctions") // 窗口生命周期操作面（注册/显示/更新/隐藏/重建/隐藏态/矩形查询）职责单一
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

        /** 输入封锁期间为 true（此间新附着的窗口保持不可触摸，N216 复发收口）。 */
        private var inputBlocked = false

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
            val entry = entries.getOrPut(id) { Entry(spec, blocked = inputBlocked) }
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
            val window = entry.window
            if (window == null || channel == null) return
            if (window.channel != channel) {
                detach(entry)
                attach(id, channel)
                return
            }
            window.windowManager.updateViewLayout(
                window.view,
                buildOverlayParams(window.channel, spec, hidden, entry.blocked),
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
                    buildOverlayParams(window.channel, entry.spec, value, entry.blocked),
                )
            }
        }

        /**
         * 输入封锁（N216）：注入期间全部自家窗口一律不可触摸（保持可见，不闪烁），
         * 避免它们把注入的点击吞掉（实测进度窗遮挡导致点击不生效）。
         * 不按注入区域收窄（P2 收窄已回退）：吞注入的机制不止"落点在窗口矩形内"——
         * z 序遮挡、聚焦输入、矩形失真都会让注入失效，且注入窗口只有毫秒级，
         * 全部封锁的代价可忽略。封锁期间新附着的窗口继承封锁态（对齐 [setHidden]）。
         * 只有封锁状态真正变化的窗口才会更新布局，避免每次动作对所有窗口做无效重排。
         */
        public fun setInputBlocked(value: Boolean) {
            inputBlocked = value
            entries.values.forEach { entry ->
                if (entry.blocked != value) {
                    entry.blocked = value
                    entry.window?.let { window ->
                        window.windowManager.updateViewLayout(
                            window.view,
                            buildOverlayParams(window.channel, entry.spec, hidden, value),
                        )
                    }
                }
            }
        }

        /** 仍可触摸的自家窗口 id（封锁自检留痕用；注入期应为空）。 */
        public fun touchableWindowIds(): List<String> = touchableEntries().map { (id, _) -> id }

        /** 显示中、未隐藏、未封锁且可触摸的窗口条目。 */
        private fun touchableEntries(): List<Map.Entry<String, Entry>> =
            entries.entries
                .filter { (_, entry) -> !hidden && !entry.blocked && entry.spec.touchable && entry.window != null }

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
            val owner = OverlayLifecycleOwner()
            val view = factory(contextFor(channel), owner)
            val params = buildOverlayParams(channel, entry.spec, hidden, entry.blocked)
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
    /** 输入封锁（注入期间不可触摸，仍可见）；按窗口单独记录，只更新变化的窗口。 */
    var blocked: Boolean = false,
) {
    var window: AttachedWindow? = null
}

/** 已附着到 WindowManager 的窗口。 */
private class AttachedWindow(
    val view: View,
    val owner: OverlayLifecycleOwner,
    val windowManager: WindowManager,
    val channel: OverlayChannel,
)
