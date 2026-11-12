/**
 * 观测采集＋序列化（agent-harness-overhaul WP2.8）：从 [ScreenObserver] 提取的单方式准备，
 * 观测入口与回放探针共用；不做历史记录。
 */
package com.example.autop.device.impl.observation

import android.graphics.BitmapFactory
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ObserveMode
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.NodeIdAssigner
import com.example.autop.device.core.NodeIds
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.deviceHistory
import com.example.autop.device.impl.vision.FrameSignature
import com.example.autop.device.impl.vision.frameSignatureOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** 采集＋序列化的中间结果（未写历史）。 */
internal class Prepared(
    val mode: ObserveMode,
    val snapshot: ScreenSnapshot,
    val serialized: SerializedScreen,
    val frame: FrameSignature?,
)

/** 单个观测方式的采集＋序列化。 */
@Singleton
public class ScreenPreparer
    @Inject
    constructor(
        private val screenReader: ScreenReader,
        private val capturer: SnapshotCapturer,
        private val contentAreas: ContentAreaProvider,
        private val visualParser: VisualScreenParser,
        private val serializer: SnapshotSerializer,
    ) {
        /** 按方式准备一次观测（未写历史）。 */
        internal suspend fun prepare(
            ctx: ToolContext,
            mode: ObserveMode,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> =
            when (mode) {
                ObserveMode.A11Y -> a11y(ctx.config, effective, idAssignerOf(ctx, ctx.config))
                ObserveMode.VISUAL -> visual(ctx.config, effective)
            }

        /**
         * a11y 节点 id 分配器（coordinate-node-action-split §3.2）：取 `history.last` 作 prev，
         * 仅当其为 a11y 且包名、Activity 与当前相同时配对沿用 id；计数器在 Run 内单调递增。
         * 只有观测入口传分配器；回放探针等直接调 [a11y] 的调用点不传。
         */
        private fun idAssignerOf(
            ctx: ToolContext,
            config: ConfigSnapshot,
        ): NodeIdAssigner {
            val history = ctx.deviceHistory()
            val prevRecorded = history.last
            val serializerConfig = serializer.configOf(config)
            return NodeIdAssigner { model, packageName, activity ->
                val prevComparable =
                    prevRecorded != null &&
                        prevRecorded.mode == ObserveMode.A11Y &&
                        prevRecorded.screen.observation.packageName == packageName &&
                        prevRecorded.screen.observation.activity == activity
                NodeIds.assign(
                    model = model,
                    prev = prevRecorded?.screen?.model,
                    prevComparable = prevComparable,
                    tolerance = serializerConfig.positionTolerance,
                    volatilePatterns = serializerConfig.volatilePatterns,
                    next = { history.allocateNodeId() },
                )
            }
        }

        /** 无障碍观测：应用窗口缺失/空树照常返回（是否换方式由自动选择决定）。 */
        internal suspend fun a11y(
            config: ConfigSnapshot,
            effective: List<ObserveMode>,
            ids: NodeIdAssigner? = null,
        ): Outcome<Prepared> =
            when (val got = screenReader.snapshot()) {
                is Outcome.Err -> got
                is Outcome.Ok -> {
                    val snap = got.value
                    val noAppWindow = snap.windows.none { it.type == WindowType.APPLICATION }
                    Outcome.Ok(
                        Prepared(
                            mode = ObserveMode.A11Y,
                            snapshot = snap,
                            serialized =
                                serializer.serialize(
                                    snap,
                                    ObserveMode.A11Y,
                                    config,
                                    effective,
                                    noAppWindow = noAppWindow,
                                    idAssigner = ids,
                                ),
                            frame = null,
                        ),
                    )
                }
            }

        /** 视觉观测：截图 → 视觉流水线 → 伪树；截图错误原样返回，解析失败用独立错误码。 */
        @Suppress(
            "ReturnCount", // 截图错误、超时、非取消异常、不可解析各自失败出口，是失败分流语义
            "TooGenericExceptionCaught", // 解析器实现可能抛任意运行时异常，统一转 VISUAL_PARSE_FAILED（WP7.6）
        )
        internal suspend fun visual(
            config: ConfigSnapshot,
            effective: List<ObserveMode>,
        ): Outcome<Prepared> {
            val base = capturer.buildSnapshot()
            val shot =
                when (val got = screenReader.screenshot()) {
                    is Outcome.Err -> return got
                    is Outcome.Ok -> got.value
                }
            val timeoutMs = config[DeviceConfig.VISION].timeoutMs
            val tree =
                try {
                    withTimeout(timeoutMs) { visualParser.synthesize(frameOf(shot, base)) }
                } catch (e: TimeoutCancellationException) {
                    return Outcome.Err(visualParseFailed("visual parse timeout (${timeoutMs}ms)", e))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.Err(visualParseFailed(e.message ?: e.javaClass.simpleName, e))
                } ?: return Outcome.Err(visualParseFailed("visual parse failed: unreadable frame", null))
            val snap = adoptVisualTree(base, tree)
            return Outcome.Ok(
                Prepared(
                    mode = ObserveMode.VISUAL,
                    snapshot = snap,
                    serialized = serializer.serialize(snap, ObserveMode.VISUAL, config, effective),
                    frame = signatureOf(shot),
                ),
            )
        }

        /** 视觉伪树替换：保留前台/尺寸信息，窗口收敛为单个 APPLICATION 窗口（§3.2）。 */
        private fun adoptVisualTree(
            base: ScreenSnapshot,
            tree: VisualTree,
        ): ScreenSnapshot {
            val window =
                WindowSnapshot(
                    windowId = 0,
                    type = WindowType.APPLICATION,
                    layer = 0,
                    packageName = base.foregroundPackage,
                    title = base.foregroundPackage,
                    bounds = Rect(0, 0, base.screenWidth, base.screenHeight),
                    active = true,
                    focused = true,
                    rootIdx = tree.rootIdx,
                )
            return base.copy(windows = listOf(window), nodes = tree.nodes, truncated = false)
        }

        private fun frameOf(
            shot: Screenshot,
            base: ScreenSnapshot,
        ): VisualFrame =
            VisualFrame(
                imageBytes = shot.jpegBytes ?: ByteArray(0),
                width = shot.width,
                height = shot.height,
                rotation = base.rotation,
                capturedAt = base.capturedAt,
                contentArea = contentAreas.base(),
            )

        private fun signatureOf(shot: Screenshot): FrameSignature? {
            val bytes = shot.jpegBytes ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return try {
                frameSignatureOf(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        private fun visualParseFailed(
            message: String,
            cause: Throwable?,
        ): AppError =
            AppError(
                code = DeviceErrorCodes.VISUAL_PARSE_FAILED,
                message = message,
                retryable = true,
                cause = cause,
            )

        private companion object {
            const val VAR_REASON = "reason"
            const val VAR_SUGGEST = "suggest"
        }
    }
