/**
 * 无障碍服务（docs/tech/05 §2.2）：只做转发——事件推给 [A11yEventBus]、
 * Activity 级窗口切换更新 [ForegroundTracker]，其余逻辑在采集/动作类中。
 */
package com.example.autop.device.impl

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityWindowInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** AutoP 无障碍服务：连接管理与事件转发。 */
@AndroidEntryPoint
public class AutopAccessibilityService : AccessibilityService() {
    @Inject
    internal lateinit var holder: ServiceHolder

    @Inject
    internal lateinit var eventBus: A11yEventBus

    @Inject
    internal lateinit var foreground: ForegroundTracker

    /** className 是否为 Activity 的缓存（key = "包名/类名"）。 */
    private val activityCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        holder.register(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event ?: return
        val pkg = source.packageName?.toString()
        val cls = source.className?.toString()
        eventBus.emit(
            A11yEventLite(
                type = source.eventType,
                packageName = pkg,
                className = cls,
                time = source.eventTime,
                isSelfOrigin = pkg != null && pkg == packageName,
            ),
        )
        trackActivity(source, pkg, cls)
    }

    private fun trackActivity(
        source: AccessibilityEvent,
        pkg: String?,
        cls: String?,
    ) {
        if (source.eventType != TYPE_WINDOW_STATE_CHANGED) return
        if (pkg != null && cls != null && isActivity(pkg, cls)) {
            foreground.onActivityResumed(pkg, cls)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        holder.unregister()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        holder.unregister()
        super.onDestroy()
    }

    /** 当前窗口列表（采集与定位解析使用）。 */
    internal fun windowList(): List<AccessibilityWindowInfo> = windows.orEmpty()

    private fun isActivity(
        pkg: String,
        cls: String,
    ): Boolean = activityCache.getOrPut("$pkg/$cls") { resolveIsActivity(pkg, cls) }

    private fun resolveIsActivity(
        pkg: String,
        cls: String,
    ): Boolean =
        try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        }
}
