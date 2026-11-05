/**
 * `tool.input_text`（docs/design/observation-modes-coordinate-actions §5.3）：
 * 参数只有坐标；执行时写入当前焦点输入框。给了 `x,y` 先按 tap 同一路径点击并等待稳定；
 * 焦点节点不可用时走 shell 剪贴板粘贴，shell 不可用返回 `device.not_editable`。
 */
package com.example.autop.device.impl.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autop.agent.PreparedAction
import com.example.autop.agent.ScreenActionTool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.GestureController
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.Rect
import com.example.autop.device.ResolvedNode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellState
import com.example.autop.device.core.PixelSize
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.DirectResolvedNode
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.ReplayInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** prepare 生成的执行载荷。 */
private data class InputAction(
    val text: String,
    val append: Boolean,
    val submit: Boolean,
    val waitMs: Long?,
    /** 点击的输入框落点（已抖动，屏幕像素）；没给坐标时为 null。 */
    val clickPoint: Point?,
    val clickDurationMs: Long,
    /** 命中回执行（没给坐标时不带）。 */
    val receipt: String?,
    val replayArgs: JsonObject,
)

/** 输入工具。 */
@Singleton
public class InputTextTool
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val screenReader: ScreenReader,
        private val gestures: GestureController,
        private val shell: ShellInput,
        private val subjects: PolicySubjectBuilder,
        private val epilogue: ActionEpilogue,
        private val coords: CoordSpaceResolver,
        private val contentAreas: ContentAreaProvider,
        private val receipts: HitReceiptBuilder,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.inputText

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val text = args.str("text") ?: return invalidArg("必须提供 text")
            val click = coordinateOf(args)
            if (click == ClickArg.Malformed) return invalidArg("x、y 必须同时提供且为整数")
            val mode = args.str("mode")
            if (mode != null && mode !in MODES) return invalidArg("mode 必须是 replace 或 append")
            return when (val got = screenReader.snapshotOrDegraded()) {
                is Outcome.Err -> got
                is Outcome.Ok -> prepared(got.value, text, click, args, ctx)
            }
        }

        private fun prepared(
            snap: ScreenSnapshot,
            text: String,
            click: ClickArg,
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val base = contentAreas.base()
            val mapper = coords.mapper(PixelSize(snap.screenWidth, snap.screenHeight), base)
            val area = base ?: Rect(0, 0, snap.screenWidth, snap.screenHeight)
            val tree = receipts.treeOf(ctx, snap)
            val entry =
                when (click) {
                    is ClickArg.None -> Entry(clickPoint = null, hit = null, receipt = null, modelPoint = null)
                    is ClickArg.Malformed -> return invalidArg("x、y 必须同时提供且为整数")
                    is ClickArg.Coordinate -> {
                        val target = mapper.toScreen(click.x, click.y) ?: return coordOutOfRange(coords)
                        val modelPoint = Point(click.x, click.y)
                        val actual =
                            jitteredTapPoint(target, receipts.bounds(tree, modelPoint, mapper), area, snap, ctx)
                        val actualModel = mapper.toNormalized(actual.x, actual.y)
                        Entry(
                            clickPoint = actual,
                            hit = receipts.describe(tree, actualModel),
                            receipt = receipts.receipt(tree, actualModel),
                            modelPoint = modelPoint,
                        )
                    }
                }
            val targetNode =
                entry.modelPoint?.let { mapper.toScreen(it.x, it.y)?.let { p -> nodeAt(snap, p.x, p.y) } }
                    ?: focusedEditable(snap)
            val subject =
                subjects.build(
                    ID,
                    snap,
                    targetNode,
                    mapOf(EXTRA_INPUT_LENGTH to text.length.toString()),
                    fallbackTree = tree?.model,
                    fallbackPoint = entry.modelPoint,
                )
            val action =
                InputAction(
                    text = text,
                    append = args.str("mode") == MODE_APPEND,
                    submit = args.bool("submit") == true,
                    waitMs = args.long("wait_ms"),
                    clickPoint = entry.clickPoint,
                    clickDurationMs = tapDurationOf(ctx, long = false),
                    receipt = entry.receipt,
                    replayArgs = replayArgsOf(args, entry.hit),
                )
            return Outcome.Ok(PreparedAction(subject, action))
        }

        /** 坐标参数：未提供、缺一半或非整数（[ClickArg.Malformed]）。 */
        private fun coordinateOf(args: JsonObject): ClickArg {
            val hasX = args.containsKey("x")
            val hasY = args.containsKey("y")
            if (!hasX && !hasY) return ClickArg.None
            val x = args.int("x")
            val y = args.int("y")
            if (x == null || y == null) return ClickArg.Malformed
            return ClickArg.Coordinate(x, y)
        }

        private suspend fun writeText(input: InputAction): Outcome<String> {
            val focused = gestures.focusedInput()
            return try {
                if (focused != null && focused.snapshot.flags and NodeFlags.EDITABLE != 0) {
                    focusedWrite(focused, input)
                } else {
                    shellWrite(input)
                }
            } finally {
                (focused as? DirectResolvedNode)?.close()
            }
        }

        private suspend fun focusedWrite(
            node: ResolvedNode,
            input: InputAction,
        ): Outcome<String> {
            val current = node.currentText() ?: node.snapshot.text.orEmpty()
            val value = if (input.append) current + input.text else input.text
            val ok =
                gestures.performNodeAction(
                    node,
                    ACTION_SET_TEXT,
                    mapOf(ACTION_ARGUMENT_SET_TEXT to value),
                )
            if (!ok) {
                return Outcome.Err(AppError(code = DeviceErrorCodes.ACTION_FAILED, message = "写入文字失败"))
            }
            return Outcome.Ok(typedText(input) + submitOf(node, input))
        }

        /** 焦点节点不可用：shell 剪贴板粘贴；通道未连接返回 `device.not_editable`。 */
        private suspend fun shellWrite(input: InputAction): Outcome<String> {
            if (shell.state.value != ShellState.Connected) {
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.NOT_EDITABLE,
                        message = "没有找到可输入的输入框",
                        hint = NOT_EDITABLE_HINT,
                    ),
                )
            }
            clipboard().setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, input.text))
            val ok =
                if (input.append) {
                    shell.keyEvent(KEYCODE_MOVE_END) && shell.keyEvent(KEYCODE_PASTE)
                } else {
                    shell.keyEvent(KEYCODE_MOVE_END) &&
                        shell.keyCombination(KEYCODE_CTRL_LEFT, KEYCODE_A) &&
                        shell.keyEvent(KEYCODE_DEL) &&
                        shell.keyEvent(KEYCODE_PASTE)
                }
            if (!ok) {
                return Outcome.Err(AppError(code = DeviceErrorCodes.ACTION_FAILED, message = "通过 shell 输入文字失败"))
            }
            return Outcome.Ok(typedText(input) + shellSubmitOf(input))
        }

        private suspend fun submitOf(
            node: ResolvedNode,
            input: InputAction,
        ): String {
            if (!input.submit) return ""
            val hasImeEnter = node.snapshot.actions.contains(ACTION_IME_ENTER)
            return when {
                hasImeEnter && gestures.performNodeAction(node, ACTION_IME_ENTER) -> SUBMIT_OK
                !hasImeEnter && shell.state.value == ShellState.Connected && shell.keyEvent(KEYCODE_ENTER) -> SUBMIT_OK
                else -> SUBMIT_FAILED
            }
        }

        private suspend fun shellSubmitOf(input: InputAction): String {
            if (!input.submit) return ""
            return if (shell.keyEvent(KEYCODE_ENTER)) SUBMIT_OK else SUBMIT_FAILED
        }

        private fun typedText(input: InputAction): String =
            if (input.append) "已追加 \"${input.text}\"" else "已输入 \"${input.text}\""

        private fun focusedEditable(snap: ScreenSnapshot): NodeSnapshot? =
            snap.nodes.firstOrNull { it.has(NodeFlags.FOCUSED) && it.has(NodeFlags.EDITABLE) }
                ?: snap.nodes.firstOrNull { it.has(NodeFlags.EDITABLE) }

        private fun clipboard(): ClipboardManager = context.getSystemService(ClipboardManager::class.java)

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val input = action.payload as InputAction
            val pre = epilogue.begin()
            val gesture =
                input.clickPoint?.let {
                    if (!gestures.tap(it.x, it.y, input.clickDurationMs)) {
                        return errorOutput(
                            AppError(code = DeviceErrorCodes.GESTURE_CANCELLED, message = "点击手势被取消"),
                        )
                    }
                    epilogue.awaitStable(ctx)
                    gestureJson(it.x, it.y, input.clickDurationMs)
                }
            val typed =
                when (val got = writeText(input)) {
                    is Outcome.Err -> return errorOutput(got.error)
                    is Outcome.Ok -> got.value
                }
            val text = listOfNotNull(typed, input.receipt).joinToString("\n")
            return epilogue.finish(ctx, text, input.waitMs, ReplayInput(ID, pre, input.replayArgs, gesture))
        }

        private fun NodeSnapshot.has(flag: Int): Boolean = flags and flag != 0

        private sealed interface ClickArg {
            data object None : ClickArg

            data object Malformed : ClickArg

            data class Coordinate(
                val x: Int,
                val y: Int,
            ) : ClickArg
        }

        private data class Entry(
            val clickPoint: Point?,
            val hit: JsonObject?,
            val receipt: String?,
            val modelPoint: Point?,
        )

        private companion object {
            const val ID = "tool.input_text"
            const val EXTRA_INPUT_LENGTH = "inputLength"
            const val MODE_APPEND = "append"
            const val CLIP_LABEL = "autop"
            const val NOT_EDITABLE_HINT =
                "没有找到可输入的输入框，请先点击输入框，或在 input_text 里给出输入框的坐标。"
            const val SUBMIT_OK = "，已回车发送"
            const val SUBMIT_FAILED = "；未能提交，请点击发送按钮"
            val MODES = setOf("replace", "append")
            val ACTION_SET_TEXT: Int = AccessibilityNodeInfo.ACTION_SET_TEXT
            val ACTION_IME_ENTER: Int = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            const val ACTION_ARGUMENT_SET_TEXT: String = AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
            const val KEYCODE_CTRL_LEFT: Int = 113
            const val KEYCODE_A: Int = 29
            const val KEYCODE_MOVE_END: Int = 123
            const val KEYCODE_DEL: Int = 67
            const val KEYCODE_ENTER: Int = 66
            const val KEYCODE_PASTE: Int = 279
        }
    }
