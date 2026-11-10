/**
 * `tool.screenshot`（docs/design/observation-modes-coordinate-actions §6.3）：
 * 截图 → 可选裁剪 → 可选落点准星 → JPEG；返回坐标说明 + Image。
 * 不再画编号框、刻度尺或网格。
 */
package com.example.autop.device.impl.tools

import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.Capability
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.Rect
import com.example.autop.device.Screenshot
import com.example.autop.device.core.PixelSize
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.ProcessedShot
import com.example.autop.device.impl.ScreenshotProcessor
import com.example.autop.device.impl.ScreenshotTaker
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.markPointOf
import com.example.autop.device.impl.pixelCropOf
import com.example.autop.llm.Block
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** 截图工具。 */
@Singleton
public class ScreenshotTool
    @Inject
    constructor(
        private val taker: ScreenshotTaker,
        private val processor: ScreenshotProcessor,
        private val contentAreas: ContentAreaProvider,
        private val prompts: PromptRepository,
        private val logger: Logger,
    ) : Tool {
        override val requiresModelVision: Boolean = true
        override val id: String = "tool.screenshot"

        override val spec = DeviceToolSpecs.screenshot

        override val access: ToolAccess = ToolAccess.READ_SCREEN

        override val requiredCapabilities: Set<Capability> = setOf(Capability.ACCESSIBILITY_SCREENSHOT)

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            logger.i(TAG, "screenshot_reason", "reason" to args.str("reason").orEmpty())
            val region =
                when (val parsed = regionOf(args.obj("region"))) {
                    is RegionArg.Malformed -> return toolError(CommonErrorCodes.INVALID_ARGUMENT, MALFORMED_REGION)
                    is RegionArg.Value -> parsed.rect
                    is RegionArg.None -> null
                }
            val mark =
                when (val parsed = markOf(args.obj("mark_point"))) {
                    is MarkArg.Malformed -> return toolError(CommonErrorCodes.INVALID_ARGUMENT, MALFORMED_MARK)
                    is MarkArg.Value -> parsed
                    is MarkArg.None -> null
                }
            return when (val got = taker.take()) {
                is Outcome.Err -> errorOutput(got.error)
                is Outcome.Ok -> render(got.value, region, mark, ctx)
            }
        }

        private suspend fun render(
            shot: Screenshot,
            region: Rect?,
            mark: MarkArg.Value?,
            ctx: ToolContext,
        ): ToolOutput {
            val bitmap = shot.handle as? android.graphics.Bitmap
            return if (bitmap == null) {
                toolError(DeviceErrorCodes.SCREENSHOT_FAILED, "截图数据不可用")
            } else {
                val screen = PixelSize(bitmap.width, bitmap.height)
                val base = contentAreas.base()
                val point = mark?.let { markPointOf(screen, it.x, it.y, base) }
                val processed =
                    processor.process(
                        bitmap,
                        pixelCropOf(bitmap, region, base),
                        listOfNotNull(point),
                    )
                bitmap.recycle()
                val note = coordNote(region != null)
                val image = save(ctx, processed, note)
                ToolOutput(
                    ok = true,
                    content = listOf(Block.Text(note), image),
                    data = buildJsonObject { put("screenshot", true) },
                )
            }
        }

        private suspend fun save(
            ctx: ToolContext,
            processed: ProcessedShot,
            alt: String,
        ): Block.Image {
            val history = ctx.deviceHistory()
            history.imageSeq += 1
            val fileRef = ctx.threadStore.saveBlob(ctx.threadId, "img/${history.imageSeq}-1.jpg", processed.jpegBytes)
            return Block.Image(
                fileRef = fileRef,
                mime = "image/jpeg",
                width = processed.width,
                height = processed.height,
                alt = alt,
            )
        }

        /** 坐标说明：整屏模板；指定 region 时追加局部说明。 */
        private fun coordNote(hasRegion: Boolean): String {
            val coord =
                prompts
                    .render(PROMPT_COORD_NOTE, emptyMap())
                    .getOrNull()
                    ?.text
                    .orEmpty()
            if (!hasRegion) return coord
            val regionNote =
                prompts
                    .render(PROMPT_REGION_NOTE, emptyMap())
                    .getOrNull()
                    ?.text
                    .orEmpty()
            return listOf(coord, regionNote).filter { it.isNotEmpty() }.joinToString("\n")
        }

        private fun markOf(mark: JsonObject?): MarkArg {
            if (mark == null) return MarkArg.None
            val x = mark.int("x")
            val y = mark.int("y")
            if (x == null || y == null) return MarkArg.Malformed
            return if (x in COORD_RANGE && y in COORD_RANGE) MarkArg.Value(x, y) else MarkArg.Malformed
        }

        private sealed interface MarkArg {
            data object None : MarkArg

            data object Malformed : MarkArg

            data class Value(
                val x: Int,
                val y: Int,
            ) : MarkArg
        }

        private companion object {
            const val TAG = "ScreenshotTool"
            const val PROMPT_COORD_NOTE = "device.screenshot.coord_note"
            const val PROMPT_REGION_NOTE = "device.screenshot.region_note"
            const val MALFORMED_REGION = "region 必须是 [x1,y1,x2,y2]（0–1000，x1<x2、y1<y2）"
            const val MALFORMED_MARK = "mark_point 必须是 {x,y}（0–1000）"
        }
    }
