/**
 * 导航跳转的安全执行与日志（docs/tech/02 §4.4，questions.md N165）：
 * 系统设置页/厂商页面在部分 ROM 上不存在，跳转失败记日志不崩溃。
 */
package com.example.autop

import android.content.Context
import android.content.Intent
import com.example.autop.core.common.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 导航日志 tag。 */
internal const val TAG_NAV: String = "AppNavHost"

/** 系统设置页安全跳转（厂商 ROM 对设置页支持不一）：失败记日志不崩溃。 */
internal fun startSettingsSafely(
    context: Context,
    intent: Intent,
) {
    runCatching { context.startActivity(intent) }
        .onFailure {
            context.navLogger().w(TAG_NAV, "settings_page_unreachable", "action" to intent.action, error = it)
        }
}

/** 导航日志源（navigator 是普通对象不经注入，经 Hilt EntryPoint 取 [Logger]）。 */
internal fun Context.navLogger(): Logger =
    EntryPointAccessors
        .fromApplication(applicationContext, AppNavLoggerEntryPoint::class.java)
        .logger()

/** [Logger] 取用入口。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AppNavLoggerEntryPoint {
    fun logger(): Logger
}
