/**
 * llm 观测模式（docs/design/observation-modes-coordinate-actions §4）：
 * 独立 VISION 请求识别截图元素 → 后处理成统一节点树；结果只在 `observe(mode=llm)` 使用。
 */
package com.example.autop.device.impl.vision.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenWindow
import com.example.autop.device.VisualFrame
import com.example.autop.device.core.BboxOrder
import com.example.autop.device.core.LlmElementNormalizer
import com.example.autop.device.core.LlmNormalizerConfig
import com.example.autop.device.core.PixelSize
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.device.core.ScreenModelBuilder
import com.example.autop.device.core.VisionSizing
import com.example.autop.device.impl.SerializerRulesLoader
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.pixelCropOf
import com.example.autop.device.impl.vision.FrameSignature
import com.example.autop.device.impl.vision.VisualFrameDiff
import com.example.autop.device.impl.vision.visionImageOf
import com.example.autop.llm.Block
import com.example.autop.llm.CoordSpace
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.LlmProvider
import com.example.autop.llm.LlmRequest
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelRole
import com.example.autop.llm.Msg
import com.example.autop.llm.Role
import com.example.autop.llm.SystemPart
import com.example.autop.llm.ToolChoice
import com.example.autop.llm.ToolSchema
import com.example.autop.llm.complete
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** 截图 → 视觉大模型解析 → 统一屏幕模型。 */
@Singleton
public class LlmScreenParser
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val gateway: LlmGateway,
        private val prompts: PromptRepository,
        private val rules: SerializerRulesLoader,
        private val configProvider: ConfigProvider,
        private val dispatchers: DispatcherProvider,
        private val clock: Clock,
    ) {
        /**
         * 解析 [frame]（已裁好基准区信息）；[region] 为内容区 0–1000 的局部区域。
         * 失败统一返回 `device.llm_parse_failed`（§4.1）。
         */
        public suspend fun parse(
            ctx: ToolContext,
            frame: VisualFrame,
            region: Rect?,
            app: String,
            page: String?,
            effectiveModes: List<ObserveMode>,
        ): Outcome<ScreenModel> {
            val provider = providerOf() ?: return Outcome.Err(parseFailed("视觉模型不可用"))
            val profile = provider.profile
            val observe = configProvider.current()[DeviceConfig.OBSERVE]
            val llm = observe.llm
            val prepared =
                withContext(dispatchers.default) { prepareImage(frame, region, profile, ctx) }
                    ?: return Outcome.Err(parseFailed("截图解码/编码失败"))
            val history = ctx.deviceHistory()
            val now = clock.nowMillis()
            val cached = cachedModel(history.llmCache, prepared.signature, frame.rotation, region, now, llm.cacheTtlMs)
            if (cached != null) {
                prepared.file.delete()
                return Outcome.Ok(
                    ScreenModelBuilder.of(
                        mode = ObserveMode.LLM,
                        windows = cached.windows,
                        effectiveModes = effectiveModes,
                        cached = true,
                    ),
                )
            }
            val model =
                try {
                    request(provider, profile, prepared, region, PageRef(app, page), effectiveModes)
                } catch (e: TimeoutCancellationException) {
                    return Outcome.Err(parseFailed("识别超时（${llm.timeoutMs}ms）：${e.message}"))
                } finally {
                    prepared.file.delete()
                }
            if (model != null) {
                history.llmCache.entry =
                    com.example.autop.device.impl.LlmObservationCache.Entry(
                        rotation = frame.rotation,
                        signature = prepared.signature,
                        region = region,
                        atMillis = now,
                        model = model,
                    )
            }
            return if (model != null) Outcome.Ok(model) else Outcome.Err(parseFailed("模型输出无法解析"))
        }

        private suspend fun request(
            provider: LlmProvider,
            profile: ModelProfile,
            prepared: PreparedImage,
            region: Rect?,
            page: PageRef,
            effectiveModes: List<ObserveMode>,
        ): ScreenModel? {
            val llm = configProvider.current()[DeviceConfig.OBSERVE].llm
            val request = requestOf(profile, prepared, region)
            var attempts = 0
            while (attempts <= llm.retries) {
                attempts += 1
                val completed =
                    withTimeout(llm.timeoutMs) {
                        provider.complete(request)
                    }
                val message = (completed as? Outcome.Ok)?.value?.message ?: continue
                val params = paramsOf(message, forceToolChoice(profile))
                val nodes =
                    params?.let {
                        LlmElementNormalizer.normalize(
                            it,
                            LlmNormalizerConfig(
                                space = profile.coordSpace,
                                order = BboxOrder.parse(profile.quirks[QUIRK_BBOX_ORDER]),
                                imageWidth = prepared.width,
                                imageHeight = prepared.height,
                                region = region,
                                maxElements = llm.maxElements,
                                minAreaRatio = llm.minAreaRatio,
                                dupIou = llm.dupIou,
                                tags = rules.get().visualTags.toSet(),
                            ),
                        )
                    }
                if (nodes != null) {
                    val window = ScreenWindow(app = page.app, page = page.activity, active = true, kids = nodes)
                    return ScreenModelBuilder.of(
                        mode = ObserveMode.LLM,
                        windows = listOf(window),
                        effectiveModes = effectiveModes,
                    )
                }
            }
            // 请求无法完成的文件由这里统一清理（成功路径在 finally）。
            return null
        }

        private fun requestOf(
            profile: ModelProfile,
            prepared: PreparedImage,
            region: Rect?,
        ): LlmRequest {
            val force = forceToolChoice(profile)
            val system = systemOf(profile, prepared)
            val systemText = if (force) system else "$system\n${render(JSON_ONLY_ID)}"
            val spec = LlmParserSpecs.spec(rules.get().visualTags)
            return LlmRequest(
                system = listOf(SystemPart(systemText)),
                messages = listOf(userMessage(prepared, region)),
                // 不支持原生 tools 时不发工具，否则请求被拒或调用被当正文。
                tools =
                    if (profile.capabilities.tools) {
                        listOf(ToolSchema(spec.name, TOOL_DESCRIPTION, spec.parameters))
                    } else {
                        emptyList()
                    },
                toolChoice = if (force) ToolChoice.required(REPORT_ELEMENTS) else ToolChoice.AUTO,
                maxOutputTokens = profile.maxOutputTokens,
            )
        }

        /** 系统提示：坐标规则按模型档位（归一化/像素 × xyxy/yxyx）选择。 */
        private fun systemOf(
            profile: ModelProfile,
            prepared: PreparedImage,
        ): String {
            val variant = variantOf(profile)
            val coordVars =
                if (profile.coordSpace == CoordSpace.IMAGE_PIXELS) {
                    mapOf("w" to prepared.width.toString(), "h" to prepared.height.toString())
                } else {
                    emptyMap()
                }
            val maxElements = configProvider.current()[DeviceConfig.OBSERVE].llm.maxElements
            return render(
                SYSTEM_ID,
                mapOf(
                    "coordRule" to render("$COORD_PREFIX$variant", coordVars),
                    "maxElements" to maxElements.toString(),
                    "tagList" to rules.get().visualTags.joinToString("、"),
                    "example" to render("$EXAMPLE_PREFIX$variant"),
                ),
            )
        }

        private fun userMessage(
            prepared: PreparedImage,
            region: Rect?,
        ): Msg {
            val user = render(USER_ID, mapOf("region" to if (region == null) "" else REGION_NOTE))
            return Msg(
                role = Role.USER,
                blocks =
                    listOf(
                        Block.Text(user),
                        Block.Image(
                            fileRef = prepared.file.path,
                            mime = MIME_JPEG,
                            width = prepared.width,
                            height = prepared.height,
                            alt = user,
                        ),
                    ),
            )
        }

        private fun paramsOf(
            message: Msg,
            force: Boolean,
        ): JsonObject? {
            val call =
                message.blocks
                    .filterIsInstance<Block.ToolCall>()
                    .firstOrNull { it.name == REPORT_ELEMENTS }
            val raw =
                call?.argsJson
                    ?: if (force) {
                        null
                    } else {
                        message.blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }
                    }
            val json = raw?.let { extractObject(it) } ?: return null
            return runCatching { AutopJson.default.parseToJsonElement(json) as? JsonObject }.getOrNull()
        }

        /** 关闭强制工具调用时从回复文本里取第一个 `{` 到最后一个 `}`（§4.3）。 */
        private fun extractObject(text: String): String? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start >= 0 && end > start) text.substring(start, end + 1) else null
        }

        private fun prepareImage(
            frame: VisualFrame,
            region: Rect?,
            profile: ModelProfile,
            ctx: ToolContext,
        ): PreparedImage? {
            val bitmap = BitmapFactory.decodeByteArray(frame.imageBytes, 0, frame.imageBytes.size) ?: return null
            return try {
                val crop = pixelCropOf(bitmap, region, frame.contentArea)
                val cropped =
                    if (crop == null) {
                        bitmap
                    } else {
                        Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
                    }
                val target = targetSize(cropped, profile)
                val scaled =
                    if (target.width == cropped.width && target.height == cropped.height) {
                        cropped
                    } else {
                        Bitmap.createScaledBitmap(cropped, target.width, target.height, true)
                    }
                val bytes = jpegOf(scaled) ?: return null
                val file = File(cacheDir(), "llm_${ctx.runId.value}_${clock.nowMillis()}.jpg")
                file.writeBytes(bytes)
                PreparedImage(
                    file = file,
                    width = scaled.width,
                    height = scaled.height,
                    signature = signatureOf(scaled),
                )
            } finally {
                bitmap.recycle()
            }
        }

        private fun targetSize(
            bitmap: Bitmap,
            profile: ModelProfile,
        ): PixelSize {
            val llm = configProvider.current()[DeviceConfig.OBSERVE].llm
            return when (profile.coordSpace) {
                CoordSpace.NORMALIZED_1000 -> {
                    val longEdge = maxOf(bitmap.width, bitmap.height)
                    if (longEdge <= llm.maxEdge) {
                        PixelSize(bitmap.width, bitmap.height)
                    } else {
                        val ratio = llm.maxEdge.toDouble() / longEdge
                        PixelSize(
                            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
                CoordSpace.IMAGE_PIXELS -> {
                    val vision = configProvider.current()[DeviceConfig.SCREENSHOT].vision
                    VisionSizing.targetSize(
                        bitmap.width,
                        bitmap.height,
                        vision.blockSize,
                        vision.maxBlocks,
                        vision.maxLongEdge,
                    )
                }
            }
        }

        private fun jpegOf(bitmap: Bitmap): ByteArray? {
            val quality = configProvider.current()[DeviceConfig.SCREENSHOT].jpegQuality
            return runCatching {
                ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
                    it.toByteArray()
                }
            }.getOrNull()
        }

        /** 与视觉链路同一帧签名判据（64×64 亮度降采样，§4.6）。 */
        private fun signatureOf(bitmap: Bitmap): FrameSignature =
            VisualFrameDiff.signature(VisualFrameDiff.grayOf(visionImageOf(bitmap)))

        private fun cacheDir(): File {
            val dir = File(context.cacheDir, "llm_observe")
            dir.mkdirs()
            return dir
        }

        private fun cachedModel(
            cache: com.example.autop.device.impl.LlmObservationCache,
            signature: FrameSignature,
            rotation: Int,
            region: Rect?,
            now: Long,
            ttlMs: Long,
        ): ScreenModel? {
            if (ttlMs <= 0) return null
            val entry = cache.entry ?: return null
            val fresh = now - entry.atMillis in 0..ttlMs
            val same =
                fresh &&
                    entry.rotation == rotation &&
                    entry.region == region &&
                    entry.signature.hash == signature.hash
            return if (same) entry.model else null
        }

        private fun providerOf(): LlmProvider? {
            val provider = (gateway.forRole(ModelRole.VISION) as? Outcome.Ok)?.value ?: return null
            return provider.takeIf { it.profile.capabilities.vision }
        }

        /** 强制工具调用仅在模型支持原生 tools 时使用；能力位为假时退文本 JSON 提取。 */
        private fun forceToolChoice(profile: ModelProfile): Boolean =
            profile.capabilities.tools &&
                !profile.quirks[QUIRK_FORCE_TOOL_CHOICE].equals("false", ignoreCase = true)

        private fun variantOf(profile: ModelProfile): String {
            val space = if (profile.coordSpace == CoordSpace.IMAGE_PIXELS) "pixels" else "normalized"
            val order = if (BboxOrder.parse(profile.quirks[QUIRK_BBOX_ORDER]) == BboxOrder.YXYX) "yxyx" else "xyxy"
            return "${space}_$order"
        }

        private fun render(
            id: String,
            vars: Map<String, String> = emptyMap(),
        ): String =
            when (val got = prompts.render(id, vars)) {
                is Outcome.Ok -> got.value.text
                is Outcome.Err -> error("missing prompt $id: ${got.error.message}")
            }

        private fun parseFailed(detail: String): AppError =
            AppError(
                code = DeviceErrorCodes.LLM_PARSE_FAILED,
                message = detail,
                retryable = true,
                hint = render(ScreenJsonWire.PROMPT_HINT_LLM_PARSE_FAILED),
            )

        private class PreparedImage(
            val file: File,
            val width: Int,
            val height: Int,
            val signature: FrameSignature,
        )

        /** 观测目标的窗口标识（写入统一 JSON 的 windows）。 */
        private data class PageRef(
            val app: String,
            val activity: String?,
        )

        private companion object {
            const val SYSTEM_ID: String = "device.llm_parser.system"
            const val USER_ID: String = "device.llm_parser.user"
            const val JSON_ONLY_ID: String = "device.llm_parser.json_only"
            const val COORD_PREFIX: String = "device.llm_parser.coord."
            const val EXAMPLE_PREFIX: String = "device.llm_parser.example."
            const val QUIRK_BBOX_ORDER: String = "bboxOrder"
            const val QUIRK_FORCE_TOOL_CHOICE: String = "forceToolChoice"
            const val MIME_JPEG: String = "image/jpeg"
            const val TOOL_DESCRIPTION: String = "报告识别到的界面元素"
            const val REGION_NOTE: String = "这张图是屏幕的一部分，坐标仍以这张图片本身为准。"
        }
    }
