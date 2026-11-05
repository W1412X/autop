/**
 * 截图（docs/tech/05 §2.6）：takeScreenshot + 错误码映射 + 最小间隔节流；
 * 截图前隐藏悬浮窗并等待 settle。
 */
package com.example.autop.device.impl

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Display
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceScreenshotSection
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.OverlayHost
import com.example.autop.device.Screenshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 一次截图的内部结果。 */
private sealed interface ShotOutcome {
    data class Success(
        val bitmap: Bitmap?,
    ) : ShotOutcome

    data class Failure(
        val errorCode: Int,
    ) : ShotOutcome
}

/** [com.example.autop.device.ScreenReader.screenshot] 的实现。 */
@Singleton
public class ScreenshotTaker
    @Inject
    constructor(
        private val holder: ServiceHolder,
        private val configProvider: ConfigProvider,
        private val overlayHost: OverlayHost,
        private val secureCache: SecureWindowCache,
        private val clock: Clock,
    ) {
        private var lastShotAt: Long = 0

        /** 截取原图（未缩放）；含 `device.screenshot.minIntervalMs` 节流。 */
        public suspend fun take(): Outcome<Screenshot> {
            val service =
                holder.current()
                    ?: return Outcome.Err(
                        AppError(
                            code = DeviceErrorCodes.SERVICE_UNAVAILABLE,
                            message = "accessibility service not connected",
                            retryable = true,
                        ),
                    )
            val cfg = configProvider.current()[DeviceConfig.SCREENSHOT]
            throttle(cfg.minIntervalMs)
            return overlayHost.hiddenDuring {
                delay(configProvider.current()[DeviceConfig.OVERLAY].hideSettleMs)
                takeWithRetry(service, cfg)
            }
        }

        private suspend fun throttle(minIntervalMs: Long) {
            val wait = lastShotAt + minIntervalMs - clock.nowMillis()
            if (wait > 0) delay(wait)
        }

        private suspend fun takeWithRetry(
            service: AccessibilityService,
            cfg: DeviceScreenshotSection,
        ): Outcome<Screenshot> {
            var attempts = 0
            while (true) {
                attempts += 1
                when (val shot = takeOnce(service)) {
                    is ShotOutcome.Success -> return success(shot.bitmap, cfg.jpegQuality)
                    is ShotOutcome.Failure ->
                        if (shot.errorCode == ERROR_INTERVAL_TIME_SHORT && attempts <= cfg.retries) {
                            delay(cfg.retryDelayMs)
                        } else {
                            return failure(shot.errorCode)
                        }
                }
            }
        }

        private fun success(
            bitmap: Bitmap?,
            jpegQuality: Int,
        ): Outcome<Screenshot> {
            lastShotAt = clock.nowMillis()
            val jpeg = bitmap?.let { jpegOf(it, jpegQuality) }
            return if (bitmap == null || jpeg == null) {
                failure(ERROR_UNKNOWN)
            } else {
                Outcome.Ok(
                    Screenshot(
                        width = bitmap.width,
                        height = bitmap.height,
                        jpegBytes = jpeg,
                        handle = bitmap,
                    ),
                )
            }
        }

        /** 原图 JPEG 编码（N178：attachScreen 读 jpegBytes 存图，此前恒缺导致 no_jpeg）。 */
        private fun jpegOf(
            bitmap: Bitmap,
            quality: Int,
        ): ByteArray? =
            runCatching {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }.getOrNull()

        private fun failure(errorCode: Int): Outcome<Screenshot> {
            val error =
                when (errorCode) {
                    ERROR_INTERVAL_TIME_SHORT ->
                        AppError(
                            code = DeviceErrorCodes.SCREENSHOT_RATE_LIMITED,
                            message = "screenshot rate limited after retries",
                            retryable = true,
                        )
                    ERROR_SECURE_WINDOW -> {
                        secureCache.markSecure()
                        AppError(
                            code = DeviceErrorCodes.SCREENSHOT_SECURE,
                            message = "secure window forbids screenshot",
                        )
                    }
                    else ->
                        AppError(
                            code = DeviceErrorCodes.SCREENSHOT_FAILED,
                            message = "takeScreenshot failed, errorCode=$errorCode",
                            retryable = true,
                        )
                }
            return Outcome.Err(error)
        }

        private suspend fun takeOnce(service: AccessibilityService): ShotOutcome =
            suspendCancellableCoroutine { cont ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    DIRECT_EXECUTOR,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            cont.resume(ShotOutcome.Success(bitmapOf(result)))
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resume(ShotOutcome.Failure(errorCode))
                        }
                    },
                )
            }

        private fun bitmapOf(result: AccessibilityService.ScreenshotResult): Bitmap? {
            val buffer: HardwareBuffer = result.hardwareBuffer ?: return null
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            buffer.close()
            return wrapped?.copy(Bitmap.Config.ARGB_8888, false)
        }

        private companion object {
            val DIRECT_EXECUTOR = Executor { it.run() }
            const val ERROR_INTERVAL_TIME_SHORT =
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
            const val ERROR_SECURE_WINDOW =
                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            const val ERROR_UNKNOWN =
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
        }
    }
