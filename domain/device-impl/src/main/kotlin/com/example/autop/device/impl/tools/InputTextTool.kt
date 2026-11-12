/**
 * `tool.input_text`（docs/design/tool_optimize/02 §3）：
 * 参数只有坐标；执行时写入当前焦点输入框。给了 `x,y` 先点击并等焦点；
 * 没有可编辑焦点、焦点不可编辑或焦点是密码框时直接失败，不做盲目剪贴板粘贴。
 * SET_TEXT 被应用拒绝时对同一个焦点做一次 shell 剪贴板粘贴回退。
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
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
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
import kotlinx.serialization.json.JsonPrimitive
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
    /** shell 粘贴后恢复剪贴板前的等待（`device.input.clipboardRestoreDelayMs`）。 */
    val clipboardRestoreDelayMs: Long,
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
@Suppress("LongParameterList") // 端口聚合类：执行链的依赖各司其职，参数只增不减（questions.md N114 口径）
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
        private val trace: ExecutionTraceRenderer,
        private val prompts: PromptRepository,
    ) : ScreenActionTool {
        override val id: String = ID

        override val spec = DeviceToolSpecs.inputText

        override val access: ToolAccess = ToolAccess.WRITE_SCREEN

        override suspend fun prepare(
            args: JsonObject,
            ctx: ToolContext,
        ): Outcome<PreparedAction> {
            val text =
                (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return invalidArg(prompts.text(PROMPT_TEXT_REQUIRED))
            if (text.isEmpty() && args.str("mode") == MODE_APPEND) {
                return invalidArg(prompts.text(PROMPT_APPEND_EMPTY))
            }
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
                    ctx.config,
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
                    clipboardRestoreDelayMs = ctx.config[DeviceConfig.INPUT].clipboardRestoreDelayMs,
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
         * 写入文字（tool_optimize §3.3）：只认 FOCUSED+EDITABLE 焦点；
         * 无焦点/不可编辑/密码框一律失败；SET_TEXT 被拒时（shell 已连）对同一焦点做剪贴板粘贴回退。
         * 失败前已发生的步骤写进 [trace]（修审计 S4）。
         */
        private suspend fun writeText(
            input: InputAction,
            steps: MutableList<String>,
        ): Outcome<WriteOutcome> {
            val focused = gestures.focusedInput()
            return try {
                when {
                    focused == null ->
                        Outcome.Err(
                            AppError(
                                code = DeviceErrorCodes.NOT_EDITABLE,
                                message = "写入前没有找到输入焦点",
                                hint = prompts.text(PROMPT_NO_FOCUS_HINT),
                            ),
                        )
                    focused.snapshot.has(NodeFlags.PASSWORD) ->
                        Outcome.Err(
                            AppError(
                                code = DeviceErrorCodes.PASSWORD_FIELD,
                                message = "输入焦点是密码框",
                                hint = prompts.text(PROMPT_PASSWORD_HINT),
                            ),
                        )
                    !focused.snapshot.has(NodeFlags.EDITABLE) ->
                        Outcome.Err(
                            AppError(
                                code = DeviceErrorCodes.NOT_EDITABLE,
                                message = "当前焦点不是输入框",
                                hint =
                                    prompts.text(
                                        PROMPT_FOCUS_NOT_EDITABLE,
                                        mapOf(
                                            VAR_CLS to focused.snapshot.className.orEmpty(),
                                            VAR_TEXT to focused.snapshot.text.orEmpty(),
                                        ),
                                    ),
                            ),
                        )
                    else -> {
                        val value =
                            if (input.append) {
                                val current = focused.currentText() ?: return Outcome.Err(refreshFailed())
                                current + input.text
                            } else {
                                input.text
                            }
                        val done = focusedWrite(focused, input, value)
                        if (done != null) {
                            Outcome.Ok(done)
                        } else {
                            steps += TRACE_SET_TEXT_REJECTED
                            shellWrite(input)
                        }
                    }
                }
            } finally {
                (focused as? DirectResolvedNode)?.close()
            }
        }

        /** ACTION_SET_TEXT 写入；返回 null 表示应用拒绝，调用方补一次 shell 粘贴。 */
        private suspend fun focusedWrite(
            node: ResolvedNode,
            input: InputAction,
            value: String,
        ): WriteOutcome? {
            val ok =
                gestures.performNodeAction(
                    node,
                    ACTION_SET_TEXT,
                    mapOf(ACTION_ARGUMENT_SET_TEXT to value),
                )
            if (!ok) return null
            val traces = mutableListOf(trace.executionLine(ExecMethod.NODE_ACTION, "ACTION_SET_TEXT"))
            val submit = submitOf(node, input, traces)
            return WriteOutcome(resultText(input, submit), traces)
        }

        /** 输入框当前内容刷新失败（WP7.11）：不拿旧文本做 append，改用 mode=replace 重新输入。 */
        private fun refreshFailed(): AppError =
            AppError(
                code = DeviceErrorCodes.ACTION_FAILED,
                message = "读取输入框当前内容失败，append 写入已放弃",
                hint = prompts.text(PROMPT_REFRESH_FAILED_HINT),
            )

        /**
         * SET_TEXT 被拒后的 shell 剪贴板粘贴回退（同一个焦点）。
         * replace 空串（清空）只发全选删除，不写剪贴板、不粘贴。
         */
        private suspend fun shellWrite(input: InputAction): Outcome<WriteOutcome> {
            if (shell.state.value != ShellState.Connected) {
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.ACTION_FAILED,
                        message = "输入框拒绝了 SET_TEXT，且 shell 通道未连接",
                        hint = prompts.text(PROMPT_SET_TEXT_REJECTED_HINT),
                    ),
                )
            }
            val clearing = input.text.isEmpty() && !input.append
            val backup = if (clearing) null else runCatching { clipboard().primaryClip }
            val saved = ClipboardBackup(backup?.getOrNull(), backup?.isSuccess ?: true)
            if (!clearing) {
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
            }
            val ok = sendKeys(input, clearing)
            val restoreNote =
                if (clearing) {
                    CLIPBOARD_UNTOUCHED
                } else {
                    restoreClipboard(saved, input.clipboardRestoreDelayMs)
                }
            if (!ok) {
                return Outcome.Err(
                    AppError(
                        code = DeviceErrorCodes.ACTION_FAILED,
                        message = "通过 shell 输入文字失败；$restoreNote",
                    ),
                )
            }
            val traces = mutableListOf<String>()
            traces += trace.executionLine(ExecMethod.SHELL_KEY, if (clearing) TRACE_CLEAR_LABEL else CLIPBOARD_PASTE)
            traces += restoreNote
            val submit = shellSubmitOf(input, traces)
            return Outcome.Ok(WriteOutcome(resultText(input, submit), traces))
        }

        /** N186/N191：注入按键走输入管道，统一经 GestureController 做盾放行＋自身动作标记。 */
        private suspend fun sendKeys(
            input: InputAction,
            clearing: Boolean,
        ): Boolean =
            when {
                clearing ->
                    gestures.shellKeyEvent(KEYCODE_MOVE_END) &&
                        gestures.shellKeyCombination(listOf(KEYCODE_CTRL_LEFT, KEYCODE_A)) &&
                        gestures.shellKeyEvent(KEYCODE_DEL)
                input.append -> gestures.shellKeyEvent(KEYCODE_MOVE_END) && gestures.shellKeyEvent(KEYCODE_PASTE)
                else ->
                    gestures.shellKeyEvent(KEYCODE_MOVE_END) &&
                        gestures.shellKeyCombination(listOf(KEYCODE_CTRL_LEFT, KEYCODE_A)) &&
                        gestures.shellKeyEvent(KEYCODE_DEL) &&
                        gestures.shellKeyEvent(KEYCODE_PASTE)
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
                traces += trace.executionLine(ExecMethod.NODE_ACTION, "ACTION_IME_ENTER")
                return SUBMIT_OK
            }
            if (shell.state.value == ShellState.Connected && gestures.shellKeyEvent(KEYCODE_ENTER)) {
                traces += trace.executionLine(ExecMethod.SHELL_KEY, "Enter")
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
                traces += trace.executionLine(ExecMethod.SHELL_KEY, "Enter")
                SUBMIT_OK
            } else {
                SUBMIT_FAILED
            }
        }

        /**
         * 恢复动作前的剪贴板（避免覆盖用户内容）。粘贴按键是异步的，先等一小段时间再恢复；
         * 无法读取原内容时明确告知，不假装已恢复。
         */
        private suspend fun restoreClipboard(
            saved: ClipboardBackup,
            delayMs: Long,
        ): String {
            if (!saved.readable) return CLIPBOARD_UNREADABLE
            val clip = saved.clip ?: return CLIPBOARD_EMPTY
            delay(delayMs)
            return if (runCatching { clipboard().setPrimaryClip(clip) }.isSuccess) {
                CLIPBOARD_RESTORED
            } else {
                CLIPBOARD_RESTORE_FAILED
            }
        }

        /** 结果行（模板 device.input.result）：verb 按模式与空串选择。 */
        private fun resultText(
            input: InputAction,
            submit: String,
        ): String {
            val verb =
                when {
                    input.append -> PROMPT_VERB_APPEND
                    input.text.isEmpty() -> PROMPT_VERB_CLEAR
                    else -> PROMPT_VERB_REPLACE
                }
            return prompts.text(
                PROMPT_RESULT,
                mapOf(VAR_VERB to prompts.text(verb), VAR_TEXT to input.text, VAR_SUBMIT to submit),
            )
        }

        /** 策略主体的焦点节点：只认 FOCUSED && EDITABLE（tool_optimize §3.2）。 */
        private fun focusedEditable(snap: ScreenSnapshot): NodeSnapshot? =
            snap.nodes.firstOrNull { it.has(NodeFlags.FOCUSED) && it.has(NodeFlags.EDITABLE) }

        private fun clipboard(): ClipboardManager = context.getSystemService(ClipboardManager::class.java)

        override suspend fun perform(
            action: PreparedAction,
            ctx: ToolContext,
        ): ToolOutput {
            val input = action.payload as InputAction
            val pre = epilogue.preSample(ctx)
            val steps = mutableListOf<String>()
            val gesture =
                input.clickPoint?.let {
                    val result = gestures.tap(it.x, it.y, input.clickDurationMs)
                    if (!result.ok) return errorOutput(injectFailed("点击输入框")).copy(trace = steps)
                    steps +=
                        prompts.text(
                            PROMPT_CLICKED,
                            mapOf(
                                VAR_X to it.x.toString(),
                                VAR_Y to it.y.toString(),
                                VAR_LINE to
                                    trace.executionLine(
                                        ExecMethod.GESTURE,
                                        channel = result.channel,
                                        shellSkip = result.shellSkip,
                                    ),
                            ),
                        )
                    epilogue.pause(ctx, ctx.config[DeviceConfig.INPUT].focusDelayMs)
                    gestureJson(it.x, it.y, input.clickDurationMs)
                }
            val written =
                when (val got = writeText(input, steps)) {
                    is Outcome.Err -> return errorOutput(got.error).copy(trace = steps)
                    is Outcome.Ok -> got.value
                }
            val text = (listOfNotNull(written.text, input.receipt)).joinToString("\n")
            return epilogue.finish(
                ctx,
                text,
                input.waitMs,
                ReplayInput(ID, input.replayArgs, gesture),
                pre,
                steps + written.traces,
            )
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
            const val TRACE_CLEAR_LABEL = "全选删除"
            const val TRACE_SET_TEXT_REJECTED = "SET_TEXT 被应用拒绝，改用 shell 粘贴"
            const val PROMPT_REFRESH_FAILED_HINT = "device.input.refresh_failed_hint"
            const val PROMPT_TEXT_REQUIRED = "device.input.text_required"
            const val PROMPT_APPEND_EMPTY = "device.input.append_empty"
            const val PROMPT_CLICKED = "device.input.clicked"
            const val PROMPT_NO_FOCUS_HINT = "device.input.no_focus_hint"
            const val PROMPT_PASSWORD_HINT = "device.input.password_field_hint"
            const val PROMPT_FOCUS_NOT_EDITABLE = "device.input.focus_not_editable"
            const val PROMPT_SET_TEXT_REJECTED_HINT = "device.input.set_text_rejected_hint"
            const val PROMPT_RESULT = "device.input.result"
            const val PROMPT_VERB_REPLACE = "device.input.verb_replace"
            const val PROMPT_VERB_APPEND = "device.input.verb_append"
            const val PROMPT_VERB_CLEAR = "device.input.verb_clear"

            const val VAR_X = "x"
            const val VAR_Y = "y"
            const val VAR_LINE = "line"
            const val VAR_CLS = "cls"
            const val VAR_TEXT = "text"
            const val VAR_VERB = "verb"
            const val VAR_SUBMIT = "submit"

            const val CLIPBOARD_RESTORED = "剪贴板：已恢复动作前的内容"
            const val CLIPBOARD_EMPTY = "剪贴板：动作前没有可保存的内容，当前保留输入文字"
            const val CLIPBOARD_UNREADABLE = "剪贴板：无法读取动作前的内容，未恢复（当前为输入文字）"
            const val CLIPBOARD_RESTORE_FAILED = "剪贴板：动作前的内容恢复失败（当前为输入文字）"
            const val CLIPBOARD_UNTOUCHED = "剪贴板：未改动"
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
