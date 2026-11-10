/**
 * 画面运动判定（agent-harness-overhaul WP2.4）：截图帧签名比较，用于 llm 收尾、
 * 滚动分步与稳定等待。与观测方式无关。
 */
package com.example.autop.device.impl.observation

import android.graphics.BitmapFactory
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceChangeSection
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.ScreenReader
import com.example.autop.device.impl.vision.FrameSignature
import com.example.autop.device.impl.vision.VisualFrameDiff
import com.example.autop.device.impl.vision.frameSignatureOf
import javax.inject.Inject
import javax.inject.Singleton

/** 截图帧签名比较。 */
@Singleton
public class FrameMotion
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val configProvider: ConfigProvider,
    ) {
        /** 截图并计算帧签名；截图失败返回 null。 */
        internal suspend fun capture(): FrameSignature? {
            val shot = screenReader.screenshot().getOrNull() ?: return null
            val bytes = shot.jpegBytes ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return try {
                frameSignatureOf(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        /** 两帧是否视为同一画面（Hamming ≤ frameMaxHamming 且 SSIM ≥ frameMinSsim）。 */
        internal fun same(
            a: FrameSignature,
            b: FrameSignature,
            cfg: DeviceChangeSection = configProvider.current()[DeviceConfig.CHANGE],
        ): Boolean = VisualFrameDiff.unchanged(a, b, cfg.frameMaxHamming, cfg.frameMinSsim)
    }
