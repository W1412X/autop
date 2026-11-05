/**
 * 悬浮窗权限状态（docs/tech/05 §10）：`Settings.canDrawOverlays` 实时检测；
 * [OverlayPermission.changes] 在每次 Activity 回到前台时重新检测后发出。
 */
package com.example.autop.device.impl.overlay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.example.autop.device.OverlayPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** [OverlayPermission] 实现（申请方式为跳转 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`）。 */
@Singleton
public class OverlayPermissionImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : OverlayPermission {
        private val _changes = MutableStateFlow(granted())

        override val changes: Flow<Boolean> = _changes.asStateFlow()

        override fun granted(): Boolean = Settings.canDrawOverlays(context)

        /** 只关心回到前台时机；其余回调为空实现（minSdk 30，接口方法带默认实现）。 */
        private val callbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    _changes.value = granted()
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }

        init {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(callbacks)
        }
    }
