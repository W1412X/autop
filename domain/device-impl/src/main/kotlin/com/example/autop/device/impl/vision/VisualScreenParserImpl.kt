/**
 * [com.example.autop.device.VisualScreenParser] 实现（docs/design/visual-screen-parsing §5.1）：
 * 解码截图 → 帧差复用（§7）→ YOLO 检测＋状态分类＋建树分析＋逐叶子 OCR/图标 → 伪树合成。
 * 引擎未就绪/解析失败返回 null，不抛错。
 */
package com.example.autop.device.impl.vision

import android.content.Context
import android.graphics.BitmapFactory
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.DeviceVisionParseSection
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 视觉屏幕解析器：截图字节 → 伪节点树（含帧差缓存）。 */
@Singleton
public class VisualScreenParserImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val configProvider: ConfigProvider,
        private val dispatchers: DispatcherProvider,
        private val debug: VisualDebugCapture,
    ) : VisualScreenParser {
        private val store = VisionModelStore(context.assets)
        private val detector: VisualDetector = OnnxVisualDetector(store)
        private val recognizer: TextRecognizer = OnnxTextRecognizer(store)
        private val states: VisualStateClassifier = OnnxStateClassifier(store)
        private val iconTable: IconLabelTable by lazy {
            val text =
                runCatching {
                    context.assets.open("rules/icon_labels.json").use { String(it.readBytes(), Charsets.UTF_8) }
                }.getOrDefault("")
            IconLabelTable.parse(text)
        }
        private val iconClassifier: VisualIconClassifier by lazy { OnnxIconClassifier(store, iconTable) }
        private val cacheLock = Any()
        private var cached: CachedTree? = null

        override val available: Boolean
            get() = configProvider.current()[DeviceConfig.VISION].enabled && detector.ready && recognizer.ready

        /**
         * 预热：把 ONNX 会话（检测/OCR 识别/状态/图标）的加载挪到空闲期。
         * 首次加载要读资产＋校验 SHA-256＋建会话（含 NNAPI 编译），放在观测调用里会撞穿解析预算。
         */
        override suspend fun warmUp() {
            val cfg = configProvider.current()[DeviceConfig.VISION]
            if (!cfg.enabled) return
            val iconCfg = configProvider.current()[DeviceConfig.VISION_ICON]
            withContext(dispatchers.io) {
                runCatching {
                    detector.ready
                    recognizer.ready
                    states.ready
                    if (iconCfg.enabled) iconClassifier.ready
                }
            }
        }

        override suspend fun synthesize(frame: VisualFrame): VisualTree? {
            val cfg = configProvider.current()[DeviceConfig.VISION]
            if (!cfg.enabled) return null
            return withContext(dispatchers.default) {
                val image = decode(frame.imageBytes) ?: return@withContext null
                // N208：调试采集期跳过帧差缓存，保证每帧都走全链路并 record 供渲染。
                if (!debug.isActive) {
                    cachedTree(image, frame, cfg)?.let { return@withContext it }
                }
                val iconCfg = configProvider.current()[DeviceConfig.VISION_ICON]
                val result =
                    VisualParsePipeline.parse(
                        image = image,
                        detector = detector,
                        recognizer = recognizer,
                        states = states,
                        options = parseOptions(cfg, frame),
                        icons = if (iconCfg.enabled) iconClassifier else null,
                    ) ?: return@withContext null
                if (debug.isActive) debug.record(image, result)
                storeTree(image, frame, result.tree)
                result.tree
            }
        }

        /** 配置 → 解析参数。 */
        private fun parseOptions(
            cfg: DeviceVisionParseSection,
            frame: VisualFrame,
        ): ParseOptions =
            ParseOptions(
                minConfidence = cfg.minConfidence,
                area = frame.contentArea,
            )

        /** 帧差缓存命中（§7：Hamming ≤ [MAX_HAMMING] 且 SSIM ≥ [MIN_SSIM] 判无变化）。 */
        private fun cachedTree(
            image: VisionImage,
            frame: VisualFrame,
            cfg: DeviceVisionParseSection,
        ): VisualTree? {
            if (!cfg.diffEnabled) return null
            val signature = VisualFrameDiff.signature(VisualFrameDiff.grayOf(image))
            return synchronized(cacheLock) {
                val hit = cached
                if (hit != null && hit.rotation != frame.rotation) {
                    null
                } else if (hit != null &&
                    VisualFrameDiff.unchanged(hit.signature, signature, MAX_HAMMING, MIN_SSIM)
                ) {
                    hit.tree.copy(cached = true)
                } else {
                    null
                }
            }
        }

        private fun storeTree(
            image: VisionImage,
            frame: VisualFrame,
            tree: VisualTree,
        ) {
            val signature = VisualFrameDiff.signature(VisualFrameDiff.grayOf(image))
            synchronized(cacheLock) { cached = CachedTree(frame.rotation, signature, tree) }
        }

        /** JPEG/PNG 字节 → ARGB 像素。 */
        private fun decode(bytes: ByteArray): VisionImage? =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { visionImageOf(it) }

        private class CachedTree(
            val rotation: Int,
            val signature: FrameSignature,
            val tree: VisualTree,
        )

        private companion object {
            const val MAX_HAMMING: Int = 6
            const val MIN_SSIM: Double = 0.98
        }
    }
