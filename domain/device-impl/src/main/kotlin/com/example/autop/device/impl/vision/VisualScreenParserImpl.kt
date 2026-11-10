/**
 * [com.example.autop.device.VisualScreenParser] 实现（docs/design/visual-screen-parsing §5.1）：
 * 解码截图 → YOLO 检测＋状态分类＋建树分析＋逐叶子 OCR/图标 → 伪树合成。
 * 引擎未就绪返回 null；推理异常向上抛（ScreenPreparer 转 visual_parse_failed，WP7.6）。
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

/** 视觉不可用的具体原因（WP7.6）：ObserveModeResolver 渲染模板文案用。 */
public sealed interface VisualUnavailable {
    /** 模型/引擎未就绪。 */
    public data object NotReady : VisualUnavailable

    /** OCR 永久停用（字典与模型不符等），[reason] 是停用原因详情。 */
    public data class OcrDisabled(
        public val reason: String,
    ) : VisualUnavailable
}

/** 视觉不可用原因查询（[VisualScreenParserImpl] 实现，WP7.6 细化“视觉解析模型未就绪”）。 */
internal interface VisualAvailability {
    /** 不可用原因；可用返回 null。 */
    fun unavailable(): VisualUnavailable?
}

/** 视觉屏幕解析器：截图字节 → 伪节点树（每次观测全链路重解析，不缓存）。 */
@Singleton
public class VisualScreenParserImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val configProvider: ConfigProvider,
        private val dispatchers: DispatcherProvider,
        private val debug: VisualDebugCapture,
    ) : VisualScreenParser,
        VisualAvailability {
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
            get() = unavailable() == null

        /** 不可用原因（WP7.6）：OCR 停用与“模型未就绪”分开报；图标识别只在启用时纳入就绪判定。 */
        override fun unavailable(): VisualUnavailable? {
            val cfg = configProvider.current()[DeviceConfig.VISION]
            if (!cfg.enabled) return VisualUnavailable.NotReady
            val ocrDisabled = recognizer.disabledReason
            if (ocrDisabled != null) return VisualUnavailable.OcrDisabled(ocrDisabled)
            val iconCfg = configProvider.current()[DeviceConfig.VISION_ICON]
            val iconReady = !iconCfg.enabled || iconClassifier.ready
            return if (detector.ready && recognizer.ready && iconReady) null else VisualUnavailable.NotReady
        }

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
