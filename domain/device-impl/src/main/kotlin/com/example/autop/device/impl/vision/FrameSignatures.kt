/**
 * 截图帧签名（agent-harness-overhaul WP1.6）：视觉观测与 llm 观测共用的签名提取，
 * 与视觉链路同一判据（64×64 亮度降采样 + aHash）。
 */
package com.example.autop.device.impl.vision

import android.graphics.Bitmap

/** 由位图计算帧签名（视觉/llm 观测记录用）。 */
internal fun frameSignatureOf(bitmap: Bitmap): FrameSignature =
    VisualFrameDiff.signature(VisualFrameDiff.grayOf(visionImageOf(bitmap)))
