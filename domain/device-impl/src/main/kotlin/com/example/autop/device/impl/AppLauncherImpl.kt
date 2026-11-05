/**
 * 应用启动（docs/tech/05 §1.3）：启动项查询、按包名启动、打开 URI。
 */
package com.example.autop.device.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 本应用身份（序列化窗口过滤用）。 */
@Singleton
public class AppIdentity
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        /** 本应用包名。 */
        public val packageName: String = context.packageName
    }

/** [AppLauncher] 实现。 */
@Singleton
public class AppLauncherImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
        private val logger: Logger,
    ) : AppLauncher {
        override suspend fun listApps(query: String?): List<AppInfo> =
            withContext(dispatchers.io) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm
                    .queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .map {
                        AppInfo(
                            packageName = it.activityInfo.packageName,
                            // vivo 主题资源读取可抛 AccessDeniedException（N162）：label 取失败回退包名。
                            label =
                                runCatching {
                                    it
                                        .loadLabel(
                                            pm,
                                        ).toString()
                                }.getOrDefault(it.activityInfo.packageName),
                        )
                    }.distinctBy { it.packageName }
                    .filter { matches(it, query) }
                    .sortedBy { it.label.lowercase() }
            }

        override suspend fun launch(packageName: String): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                logger.w(TAG, "launch_intent_missing", "packageName" to packageName)
                return false
            }
            return startSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), packageName)
        }

        override suspend fun openUri(uri: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            return startSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri)
        }

        private fun matches(
            app: AppInfo,
            query: String?,
        ): Boolean {
            val q = query?.trim()?.lowercase().orEmpty()
            return q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
        }

        private fun startSafely(
            intent: Intent,
            target: String,
        ): Boolean =
            try {
                context.startActivity(intent)
                true
            } catch (ignored: android.content.ActivityNotFoundException) {
                logger.w(TAG, "activity_not_found", "target" to target, error = ignored)
                false
            } catch (denied: SecurityException) {
                // ROM 限制/后台启动拦截（BAL 等）不再直穿成 TOOL_CRASHED（N172）。
                logger.w(TAG, "start_activity_denied", "target" to target, error = denied)
                false
            }

        private companion object {
            const val TAG = "AppLauncher"
        }
    }
