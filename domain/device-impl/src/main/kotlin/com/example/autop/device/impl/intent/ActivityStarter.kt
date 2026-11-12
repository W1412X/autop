/**
 * 启动 Activity 的统一分流（intent-tool §7.1）：找不到应用 / 被系统拒绝 / 其它异常各自给出错误码与 hint。
 * 由 [com.example.autop.device.impl.AppLauncherImpl] 与 `intent` 工具共用，分流规则只在这里维护。
 */
package com.example.autop.device.impl.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Logger
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.impl.tools.text
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** 启动 Intent 并把失败映射成 [AppError]；成功返回 null。 */
@Singleton
public class ActivityStarter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val logger: Logger,
        private val prompts: PromptRepository,
    ) {
        /**
         * 启动分流（WP7.5）：找不到应用 / 被系统拒绝 / 其它异常给出各自的错误码与 hint，不再只返回“打开失败”。
         * [genericCode] 是其它异常时使用的错误码（调用方决定是应用启动还是 URI 打开）。
         */
        @Suppress("TooGenericExceptionCaught") // startActivity 可抛任意运行时异常，统一转通用失败码（WP7.5）
        fun start(
            intent: Intent,
            target: String,
            genericCode: String,
        ): AppError? =
            try {
                context.startActivity(intent)
                null
            } catch (missing: ActivityNotFoundException) {
                logger.w(TAG, "activity_not_found", "target" to target, error = missing)
                AppError(
                    code = DeviceErrorCodes.APP_NOT_FOUND,
                    message = "no activity to handle $target",
                    hint = prompts.text(PROMPT_NOT_FOUND_HINT),
                )
            } catch (denied: SecurityException) {
                // ROM 限制/后台启动拦截（BAL 等）不再直穿成 TOOL_CRASHED（N172）。
                logger.w(TAG, "start_activity_denied", "target" to target, error = denied)
                AppError(
                    code = DeviceErrorCodes.LAUNCH_DENIED,
                    message = "start activity denied: $target (${denied.message})",
                    hint = prompts.text(PROMPT_DENIED_HINT),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (other: Exception) {
                logger.w(TAG, "start_activity_failed", "target" to target, error = other)
                AppError(code = genericCode, message = "${other.javaClass.simpleName}: ${other.message}")
            }

        private companion object {
            /** 日志标签保持与原 AppLauncherImpl 一致，便于检索。 */
            const val TAG = "AppLauncher"
            const val PROMPT_NOT_FOUND_HINT = "device.launch.not_found_hint"
            const val PROMPT_DENIED_HINT = "device.launch.denied_hint"
        }
    }
