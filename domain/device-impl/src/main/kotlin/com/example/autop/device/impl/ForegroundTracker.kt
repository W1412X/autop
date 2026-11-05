/**
 * 前台应用跟踪（docs/tech/05 §2.2）：由 TYPE_WINDOW_STATE_CHANGED 且 className 是 Activity 的事件驱动。
 */
package com.example.autop.device.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 当前前台 Activity。 */
public data class ForegroundApp(
    val packageName: String,
    val activity: String?,
)

/** 前台应用跟踪器。 */
@Singleton
public class ForegroundTracker
    @Inject
    constructor() {
        private val currentApp = MutableStateFlow<ForegroundApp?>(null)

        /** 当前前台应用；未知为 null。 */
        public val current: StateFlow<ForegroundApp?> = currentApp.asStateFlow()

        /** 收到 Activity 级窗口切换事件时更新。 */
        public fun onActivityResumed(
            packageName: String,
            activity: String?,
        ) {
            currentApp.value = ForegroundApp(packageName, activity)
        }
    }
