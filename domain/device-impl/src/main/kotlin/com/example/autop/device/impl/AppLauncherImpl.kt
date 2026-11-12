/**
 * 应用启动（docs/tech/05 §1.3）：启动项查询、按包名启动、打开 URI。
 * 失败原因分流见 agent-harness-overhaul WP7.5：找不到应用 / 被系统拒绝 / 其它异常各自错误码与 hint。
 */
package com.example.autop.device.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.autop.core.common.AppError
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.impl.intent.ActivityStarter
import com.example.autop.device.impl.tools.text
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
        private val prompts: PromptRepository,
        private val starter: ActivityStarter,
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

        override suspend fun launch(packageName: String): Boolean = launchWithError(packageName) == null

        override suspend fun openUri(uri: String): Boolean = openUriWithError(uri) == null

        override suspend fun launchWithError(packageName: String): AppError? {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                logger.w(TAG, "launch_intent_missing", "packageName" to packageName)
                return AppError(
                    code = DeviceErrorCodes.APP_NOT_FOUND,
                    message = "no launch intent for $packageName",
                    hint = prompts.text(PROMPT_NOT_FOUND_HINT),
                )
            }
            return startSafely(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                packageName,
                DeviceErrorCodes.LAUNCH_FAILED,
            )
        }

        override suspend fun openUriWithError(uri: String): AppError? {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            return startSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), uri, DeviceErrorCodes.URI_FAILED)
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

        /** 启动分流委托给 [ActivityStarter]（intent-tool §7.1）：错误码与 hint 不变。 */
        private fun startSafely(
            intent: Intent,
            target: String,
            genericCode: String,
        ): AppError? = starter.start(intent, target, genericCode)

        private companion object {
            const val TAG = "AppLauncher"
            const val PROMPT_NOT_FOUND_HINT = "device.launch.not_found_hint"
        }
    }
