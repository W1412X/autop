/**
 * 视觉链路调试图采集器（N208）：begin 开一次采集 → record 逐帧渲染「检测渲染图＋逐框识别 I/O 裁剪图」
 * 落盘 cache/probe-debug/（7 天清理）→ take 取走产物并复位。仅工具自测（ToolContext.debugImages）时启用。
 * 说明文案与逐框数据取 [VisualDebugTrace]（纯 Kotlin，可 JVM 单测）；本文件只做位图绘制与落盘。
 */
package com.example.autop.device.impl.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.autop.device.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 调试渲染产物（N208）：PNG 文件引用 + 尺寸 + 说明，展示端据此加载（工具测试面板/记录页）。 */
internal data class VisualDebugImage(
    val fileRef: String,
    val width: Int,
    val height: Int,
    val alt: String,
)

/** 逐框调试数据（N208）。 */
internal data class VisualDebugBox(
    /** 展示序号（阅读序 1 起），检测渲染图与裁剪图按此对应。 */
    val index: Int,
    val category: String,
    val confidence: Double,
    val bounds: Rect,
    /** OCR 归属文本（中心落入的最小框获胜，与 [VisualTreeBuilder] 的归属同口径）。 */
    val text: String?,
    /** 状态三态（vp8 §6.3）：on/off/未知。 */
    val checked: Boolean?,
)

/** 视觉链路调试图采集器（N208）。 */
@Singleton
public class VisualDebugCapture
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val lock = Any()
        private var active = false
        private var sessionId = 0L
        private var seq = 0
        private var images = mutableListOf<VisualDebugImage>()

        /** 采集是否进行中（解析器据此跳过帧差缓存并 record）。 */
        internal val isActive: Boolean
            get() = synchronized(lock) { active }

        /** 开始一次采集会话：清空上次产物、换会话 id，并裁掉过期文件。 */
        internal fun begin() {
            synchronized(lock) {
                active = true
                sessionId = System.currentTimeMillis()
                seq = 0
                images = mutableListOf()
            }
            pruneOld()
        }

        /** 记录一帧解析数据并渲染落盘（vp9 §6.3：补扫框/图标区域/拼图画布；失败静默忽略）。 */
        internal fun record(
            image: VisionImage,
            result: VisualParseResult,
        ) {
            val rendered = runCatching { render(image, result) }.getOrDefault(emptyList())
            synchronized(lock) { images += rendered }
        }

        /** 取走本次会话产物并复位（未采集时为空）。 */
        internal fun take(): List<VisualDebugImage> =
            synchronized(lock) {
                active = false
                val out = images.toList()
                images = mutableListOf()
                out
            }

        /**
         * 渲染一帧：先从干净帧裁逐框识别输入图，再就地标注帧画检测渲染图（补扫框黄、
         * 图标区域与结果），拼图画布单独成图（输出按检测图在前排序）。
         */
        private fun render(
            image: VisionImage,
            result: VisualParseResult,
        ): List<VisualDebugImage> {
            val boxes = boxesOf(result.detections, result.lines)
            val frame = frameBitmap(image)
            val crops = renderCrops(frame, boxes)
            val detection = renderDetection(frame, image, boxes, result)
            frame.recycle()
            val mosaic = result.mosaicCanvas?.let { renderMosaic(it) }
            return listOfNotNull(detection) + listOfNotNull(mosaic) + crops
        }

        /** 检测渲染图：红框（类别/置信度）＋绿 OCR 行＋黄补扫框＋图标区域/结果（vp9 §6.3）。 */
        private fun renderDetection(
            frame: Bitmap,
            image: VisionImage,
            boxes: List<VisualDebugBox>,
            result: VisualParseResult,
        ): VisualDebugImage? {
            val canvas = Canvas(frame)
            for (box in boxes) {
                val b = box.bounds
                canvas.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), boxPaint)
                canvas.drawText("#${box.index} ${box.category}", b.l.toFloat(), b.t + LABEL_SIZE, labelPaint)
            }
            for (line in result.lines) {
                canvas.drawText(line.text, line.bounds.l.toFloat(), line.bounds.b.toFloat(), linePaint)
            }
            for (region in result.rescanRegions) {
                val b = region
                canvas.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), rescanPaint)
            }
            for ((idx, region) in result.iconRegions) {
                val b = region
                canvas.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), iconPaint)
                val label = result.iconResults[idx]?.let { "icon=$it" } ?: "icon=?"
                canvas.drawText(label, region.l.toFloat(), region.t + LABEL_SIZE, labelPaint)
            }
            val file = savePng("det", frame) ?: return null
            val caption = detectionCaptionOf(boxes.size, result.lines.size)
            return VisualDebugImage(file.absolutePath, image.width, image.height, caption)
        }

        /** 补扫拼图画布图（vp9 §4.4）。 */
        private fun renderMosaic(canvas: VisionImage): VisualDebugImage? {
            val bmp = frameBitmap(canvas)
            val file = savePng("mosaic", bmp) ?: return null
            bmp.recycle()
            val caption = mosaicCaptionOf(canvas.width, canvas.height)
            return VisualDebugImage(file.absolutePath, canvas.width, canvas.height, caption)
        }

        /** 逐框识别 I/O 裁剪图：裁剪＝识别输入，OCR 归属/状态＝识别输出（上限 [MAX_CROP_IMAGES]）。 */
        private fun renderCrops(
            frame: Bitmap,
            boxes: List<VisualDebugBox>,
        ): List<VisualDebugImage> =
            boxes
                .take(MAX_CROP_IMAGES)
                .mapNotNull { box ->
                    val crop = box.bounds.intersect(0, 0, frame.width, frame.height)
                    if (crop.area == 0) return@mapNotNull null
                    val w = crop.r - crop.l
                    val h = crop.b - crop.t
                    val bmp = Bitmap.createBitmap(frame, crop.l, crop.t, w, h)
                    val file = savePng("crop", bmp)
                    file?.let { VisualDebugImage(it.absolutePath, w, h, captionOf(box)) }
                }

        /** 干净帧位图（ARGB 像素 → 可变 Bitmap，供标注/裁剪）。 */
        private fun frameBitmap(image: VisionImage): Bitmap {
            val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
            return bmp
        }

        /** PNG 落盘（会话 id＋自增序号防覆盖）；失败返回 null。 */
        private fun savePng(
            tag: String,
            bmp: Bitmap,
        ): File? {
            val dir = debugDir()
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val file = File(dir, "$tag-$sessionId-${nextSeq()}.png")
            return runCatching {
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                file
            }.getOrNull()
        }

        /** 裁掉 7 天前的调试图（best-effort）。 */
        private fun pruneOld() {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            runCatching {
                debugDir()
                    .listFiles()
                    .orEmpty()
                    .forEach { if (it.lastModified() < cutoff) it.delete() }
            }
        }

        private fun debugDir(): File = File(context.cacheDir, "probe-debug")

        private fun nextSeq(): Int = synchronized(lock) { seq++ }

        private companion object {
            const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
            const val PNG_QUALITY = 100
            const val LABEL_SIZE = 28f
            val boxPaint =
                Paint().apply {
                    color = Color.RED
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
            val labelPaint =
                Paint().apply {
                    color = Color.RED
                    textSize = LABEL_SIZE
                    isAntiAlias = true
                }
            val linePaint =
                Paint().apply {
                    color = Color.GREEN
                    textSize = 24f
                    isAntiAlias = true
                }

            /** 补扫框（vp9 §6.3：黄）。 */
            val rescanPaint =
                Paint().apply {
                    color = Color.YELLOW
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }

            /** 图标区域（vp9 §6.3：蓝）。 */
            val iconPaint =
                Paint().apply {
                    color = Color.CYAN
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
        }
    }
