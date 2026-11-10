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
import kotlinx.coroutines.delay
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

/** 写入结果：模型可见正文 + 实际执行通道/剪贴板处理说明。 */
private data class WriteOutcome(
    val text: String,
    val traces: List<String>,
)

/** 剪贴板备份：读取是否成功与原始内容。 */
private data class ClipboardBackup(
    val clip: ClipData?,
    val readable: Boolean,
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

        /**
         * 写入文字：焦点输入框优先 ACTION_SET_TEXT；被拒绝时补一次 shell 剪贴板粘贴并说明；
         * 没有可编辑焦点时直接走 shell，同时提示焦点不确定（可能没写进目标框）。
         */
        private suspend fun writeText(input: InputAction): Outcome<WriteOutcome> {
            val focused = gestures.focusedInput()
            return try {
                val editable = focused?.takeIf { it.snapshot.flags and NodeFlags.EDITABLE != 0 }
                when {
                    editable != null -> {
                        val done = focusedWrite(editable, input)
                        if (done != null) {
                            Outcome.Ok(done)
                        } else {
                            shellWrite(input, fallback = "焦点输入框拒绝了 ACTION_SET_TEXT，改用 shell 粘贴")
                        }
                    }
                    focused != null ->
                        shellWrite(
                            input,
                            fallback = "当前焦点节点不是可编辑输入框，改用 shell 粘贴",
                        )
                    else -> shellWrite(input, focusNote = FOCUS_UNKNOWN_NOTE)
                }
            } finally {
                (focused as? DirectResolvedNode)?.close()
            }
        }

        /** ACTION_SET_TEXT 写入；返回 null 表示应用拒绝，调用方补一次 shell 粘贴。 */
        private suspend fun focusedWrite(
            node: ResolvedNode,
            input: InputAction,
        ): WriteOutcome? {
            val current = node.currentText() ?: node.snapshot.text.orEmpty()
            val value = if (input.append) current + input.text else input.text
            val ok =
                gestures.performNodeAction(
                    node,
                    ACTION_SET_TEXT,
                    mapOf(ACTION_ARGUMENT_SET_TEXT to value),
                )
            if (!ok) return null
            val traces = mutableListOf(executionLine(ExecMethod.NODE_ACTION, "ACTION_SET_TEXT"))
            val submit = submitOf(node, input, traces)
            return WriteOutcome(typedText(input) + submit, traces)
        }

        /** 焦点节点不可用：shell 剪贴板粘贴；通道未连接返回 `device.not_editable`。 */
        private suspend fun shellWrite(
            input: InputAction,
            fallback: String? = null,
            focusNote: String? = null,
        ): Outcome<WriteOutcome> {
            if (shell.state.value != ShellState.Connected) {
                val why = fallback?.let { "（$it）" }.orEmpty()
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.NOT_EDITABLE,
                        message = "没有找到可输入的输入框$why，且 shell 注入通道未连接",
                        hint = NOT_EDITABLE_HINT,
                    ),
                )
            }
            val backup = runCatching { clipboard().primaryClip }
            val saved = ClipboardBackup(backup.getOrNull(), backup.isSuccess)
            val clipboardOk =
                runCatching { clipboard().setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, input.text)) }.isSuccess
            if (!clipboardOk) {
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.ACTION_FAILED,
                        message = "无法写入剪贴板，未能通过 shell 输入文字",
                    ),
                )
            }
            // N186/N191：注入按键走输入管道，统一经 GestureController 做盾放行＋自身动作标记。
            val ok =
                if (input.append) {
                    gestures.shellKeyEvent(KEYCODE_MOVE_END) && gestures.shellKeyEvent(KEYCODE_PASTE)
                } else {
                    gestures.shellKeyEvent(KEYCODE_MOVE_END) &&
                        gestures.shellKeyCombination(listOf(KEYCODE_CTRL_LEFT, KEYCODE_A)) &&
                        gestures.shellKeyEvent(KEYCODE_DEL) &&
                        gestures.shellKeyEvent(KEYCODE_PASTE)
                }
            val restoreNote = restoreClipboard(saved)
            if (!ok) {
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.ACTION_FAILED,
                        message = "通过 shell 输入文字失败；$restoreNote",
                    ),
                )
            }
            val traces = mutableListOf<String>()
            fallback?.let { traces += "回退：$it" }
            focusNote?.let { traces += it }
            traces += executionLine(ExecMethod.SHELL_KEY, CLIPBOARD_PASTE)
            traces += restoreNote
            val submit = shellSubmitOf(input, traces)
            return Outcome.Ok(WriteOutcome(typedText(input) + submit, traces))
        }

        /** 提交：优先 IME_ENTER 节点动作，失败或没有该动作时补一次 shell Enter；结果写进 [traces]。 */
        private suspend fun submitOf(
            node: ResolvedNode,
            input: InputAction,
            traces: MutableList<String>,
        ): String {
            if (!input.submit) return ""
            val hasImeEnter = node.snapshot.actions.contains(ACTION_IME_ENTER)
            if (hasImeEnter && gestures.performNodeAction(node, ACTION_IME_ENTER)) {
                traces += executionLine(ExecMethod.NODE_ACTION, "ACTION_IME_ENTER")
                return SUBMIT_OK
            }
            if (shell.state.value == ShellState.Connected && gestures.shellKeyEvent(KEYCODE_ENTER)) {
                traces += executionLine(ExecMethod.SHELL_KEY, "Enter")
                return SUBMIT_OK
            }
            return SUBMIT_FAILED
        }

        /** shell 路径的提交：直接 shell Enter，结果与通道写进 [traces]。 */
        private suspend fun shellSubmitOf(
            input: InputAction,
            traces: MutableList<String>,
        ): String {
            if (!input.submit) return ""
            return if (gestures.shellKeyEvent(KEYCODE_ENTER)) {
                traces += executionLine(ExecMethod.SHELL_KEY, "Enter")
                SUBMIT_OK
            } else {
                SUBMIT_FAILED
            }
        }

        /**
         * 恢复动作前的剪贴板（避免覆盖用户内容）。粘贴按键是异步的，先等一小段时间再恢复；
         * 无法读取原内容时明确告知，不假装已恢复。
         */
        private suspend fun restoreClipboard(saved: ClipboardBackup): String {
            if (!saved.readable) return CLIPBOARD_UNREADABLE
            val clip = saved.clip ?: return CLIPBOARD_EMPTY
            delay(CLIP_RESTORE_DELAY_MS)
            return if (runCatching { clipboard().setPrimaryClip(clip) }.isSuccess) {
                CLIPBOARD_RESTORED
            } else {
                CLIPBOARD_RESTORE_FAILED
            }
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
            val pre = epilogue.begin(ctx)
            val traces = mutableListOf<String>()
            val gesture =
                input.clickPoint?.let {
                    val result = gestures.tap(it.x, it.y, input.clickDurationMs)
                    if (!result.ok) return errorOutput(injectFailed("点击输入框"))
                    traces += executionLine(ExecMethod.GESTURE, channel = result.channel)
                    epilogue.awaitStable(ctx)
                    gestureJson(it.x, it.y, input.clickDurationMs)
                }
            val written =
                when (val got = writeText(input)) {
                    is Outcome.Err -> return errorOutput(got.error)
                    is Outcome.Ok -> got.value
                }
            traces += written.traces
            traces += effectLine(ExecEffect.UNKNOWN, EFFECT_UNKNOWN_NOTE)
            val text = (listOfNotNull(written.text, input.receipt) + traces).joinToString("\n")
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
            const val CLIPBOARD_PASTE = "剪贴板粘贴"
            const val NOT_EDITABLE_HINT =
                "没有找到可输入的输入框，请先点击输入框，或在 input_text 里给出输入框的坐标。"
            const val FOCUS_UNKNOWN_NOTE =
                "焦点：写入前没有找到可编辑的焦点节点，已按剪贴板粘贴处理；可能没有写进目标输入框，请用 observe 确认。"
            const val EFFECT_UNKNOWN_NOTE = "输入工具只确认写入动作被接受，不校验框内文字；需要时用 observe 确认。"
            const val CLIPBOARD_RESTORED = "剪贴板：已恢复动作前的内容"
            const val CLIPBOARD_EMPTY = "剪贴板：动作前没有可保存的内容，当前保留输入文字"
            const val CLIPBOARD_UNREADABLE = "剪贴板：无法读取动作前的内容，未恢复（当前为输入文字）"
            const val CLIPBOARD_RESTORE_FAILED = "剪贴板：动作前的内容恢复失败（当前为输入文字）"
            const val SUBMIT_OK = "，已回车发送"
            const val SUBMIT_FAILED = "；未能提交，请点击发送按钮"
            const val CLIP_RESTORE_DELAY_MS = 400L
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
