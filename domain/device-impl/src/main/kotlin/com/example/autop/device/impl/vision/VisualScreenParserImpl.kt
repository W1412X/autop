/**
 * [com.example.autop.device.VisualScreenParser] 实现（docs/design/visual-screen-parsing §5.1）：
 * 解码截图 → YOLO 检测＋状态分类＋建树分析＋逐叶子 OCR/图标 → 伪树合成。
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

/** 视觉屏幕解析器：截图字节 → 伪节点树（每次观测全链路重解析，不缓存）。 */
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

        /** JPEG/PNG 字节 → ARGB 像素。 */
        private fun decode(bytes: ByteArray): VisionImage? =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { visionImageOf(it) }
    }
