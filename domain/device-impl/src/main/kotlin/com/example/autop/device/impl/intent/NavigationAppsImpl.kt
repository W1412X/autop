/**
 * [com.example.autop.device.NavigationApps] 实现（intent-tool §5.1）。
 * 已安装判断只看包管理器：应用存在、已启用，并且有启动入口。不做缓存，安装或卸载后下一次调用即生效。
 */
package com.example.autop.device.impl.intent

import android.content.Context
import android.content.pm.PackageManager
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.device.NavigationApp
import com.example.autop.device.NavigationApps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 导航应用端口的实现。 */
@Singleton
public class NavigationAppsImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val rules: NavigationAppsRulesLoader,
        private val dispatchers: DispatcherProvider,
    ) : NavigationApps {
        override suspend fun installed(): List<NavigationApp> =
            withContext(dispatchers.io) {
                val pm = context.packageManager
                known().filter { isInstalled(pm, it.packageName) }
            }

        override fun known(): List<NavigationApp> =
            rules
                .get()
                .apps
                .sortedBy { it.priority }
                .map { NavigationApp(id = it.id, label = it.label, packageName = it.packageName) }

        /** 包不存在即未安装，不需要保留 NameNotFoundException。 */
        @Suppress("SwallowedException")
        private fun isInstalled(
            pm: PackageManager,
            packageName: String,
        ): Boolean =
            try {
                pm.getApplicationInfo(packageName, 0).enabled && pm.getLaunchIntentForPackage(packageName) != null
            } catch (missing: PackageManager.NameNotFoundException) {
                false
            }
    }
