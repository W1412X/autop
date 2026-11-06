/**
 * 无障碍服务（docs/tech/05 §2.2）：只做转发——事件推给 [A11yEventBus]、
 * Activity 级窗口切换更新 [ForegroundTracker]，其余逻辑在采集/动作类中。
 */
package com.example.autop.device.impl

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityWindowInfo
import com.example.autop.device.impl.record.RecordingGate
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

    @Inject
    internal lateinit var recordingGate: RecordingGate

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
        val captured = if (recordingGate.active) captureSource(source) else null
        eventBus.emit(
            A11yEventLite(
                type = source.eventType,
                packageName = pkg,
                className = cls,
                time = source.eventTime,
                isSelfOrigin = pkg != null && pkg == packageName,
                source = captured,
                scrollDeltaY =
                    if (recordingGate.active && source.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                        source.scrollDeltaY
                    } else {
                        null
                    },
            ),
        )
        trackActivity(source, pkg, cls)
    }

    /** 复制事件源节点的文字与边界；节点在回收前读取（录制期间才调用）。 */
    private fun captureSource(event: AccessibilityEvent): EventSource? {
        val node = event.source ?: return null
        return try {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            EventSource(
                className = node.className?.toString(),
                viewId = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                bounds =
                    com.example.autop.device
                        .Rect(r.left, r.top, r.right, r.bottom),
                isPassword = node.isPassword,
            )
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
        }
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
