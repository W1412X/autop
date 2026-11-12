/**
 * 坐标工具测试环境（coordinate-node-action-split WP3）：真实收尾链
 * （ScreenPreparer → ScreenObserver → ActionEpilogue）＋手势/读屏替身；
 * TapTool / ScrollTool 等屏幕动作工具共用。
 */
package com.example.autop.device.impl.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.autop.agent.EventPayload
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayStateSample
import com.example.autop.agent.RunScopedValues
import com.example.autop.agent.ThreadEvent
import com.example.autop.agent.ThreadStore
import com.example.autop.agent.ThreadSummary
import com.example.autop.agent.ToolContext
import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.testing.FakeClock
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.core.testing.FakePromptRepository
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.GestureController
import com.example.autop.device.GlobalAction
import com.example.autop.device.InjectionResult
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ResolvedNode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellSkip
import com.example.autop.device.ShellState
import com.example.autop.device.StableWait
import com.example.autop.device.UiStabilizer
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.VisualFrame
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.VisualTree
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.RedactionMode
import com.example.autop.device.impl.AppIdentity
import com.example.autop.device.impl.AutopAccessibilityService
import com.example.autop.device.impl.ContentAreaProvider
import com.example.autop.device.impl.CoordSpaceResolver
import com.example.autop.device.impl.ForegroundTracker
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.PolicySubjectBuilder
import com.example.autop.device.impl.SecureWindowCache
import com.example.autop.device.impl.SerializerRulesLoader
import com.example.autop.device.impl.ServiceHolder
import com.example.autop.device.impl.SnapshotCapturer
import com.example.autop.device.impl.SnapshotSerializer
import com.example.autop.device.impl.WindowRedactor
import com.example.autop.device.impl.nodeaction.NodeActionIds
import com.example.autop.device.impl.observation.ActionEpilogue
import com.example.autop.device.impl.observation.FrameMotion
import com.example.autop.device.impl.observation.ScreenObserver
import com.example.autop.device.impl.observation.ScreenPreparer
import com.example.autop.device.impl.restart.RestartRulesLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream

/** 屏幕尺寸（像素）。 */
internal const val ENV_SCREEN_WIDTH = 1080

internal const val ENV_SCREEN_HEIGHT = 1920

internal val ENV_FULL: Rect = Rect(0, 0, ENV_SCREEN_WIDTH, ENV_SCREEN_HEIGHT)

/** 坐标工具测试环境。 */
internal class ToolTestEnv {
    val provider = FakeConfigProvider(Json.parseToJsonElement("{}").jsonObject)
    val gestures = FakeGestures()
    val prompts = FakePromptRepository()
    val reader = FakeReader()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val contentAreas = ContentAreaProvider(provider)
    private val holder = ServiceHolder().apply { register(service()) }
    val serializer = SnapshotSerializer(SerializerRulesLoader(context), NoRedaction, AppIdentity(context), prompts)
    private val capturer =
        SnapshotCapturer(
            holder = holder,
            configProvider = provider,
            clock = FakeClock(0L),
            foreground = ForegroundTracker(),
            contentAreas = contentAreas,
            prompts = prompts,
            context = context,
        )
    private val preparer = ScreenPreparer(reader, capturer, contentAreas, FakeVisual(), serializer)
    private val modes = ObserveModeResolver(holder, FakeVisual(), prompts)
    val observer = ScreenObserver(preparer, serializer, modes)
    private val frameMotion = FrameMotion(reader, provider)
    val epilogue =
        ActionEpilogue(FakeStabilizer, FakeProbe, frameMotion, prompts, FakeClock(0L), FakeMonitor)

    private val subjects = PolicySubjectBuilder(SecureWindowCache(FakeClock(0L)))
    private val trace = ExecutionTraceRenderer(prompts)
    private val sharedCtx = TestContext(provider)
    private val resolver = NodeTargetResolver(reader, prompts)

    fun tapNodeTool(): TapNodeTool = TapNodeTool(resolver, gestures, subjects, epilogue, trace, prompts)

    fun scrollNodeTool(): ScrollNodeTool = ScrollNodeTool(resolver, gestures, subjects, epilogue, trace, prompts)

    fun tapTool(): TapTool =
        TapTool(
            reader,
            gestures,
            subjects,
            epilogue,
            CoordSpaceResolver(prompts),
            contentAreas,
            HitReceiptBuilder(serializer),
            trace,
            prompts,
        )

    fun scrollTool(): ScrollTool =
        ScrollTool(
            reader,
            gestures,
            subjects,
            epilogue,
            CoordSpaceResolver(prompts),
            contentAreas,
            HitReceiptBuilder(serializer),
            trace,
            prompts,
        )

    fun swipeTool(): SwipeTool =
        SwipeTool(
            reader,
            gestures,
            subjects,
            epilogue,
            CoordSpaceResolver(prompts),
            contentAreas,
            HitReceiptBuilder(serializer),
            trace,
            prompts,
        )

    fun inputTextTool(shell: ShellInput): InputTextTool =
        InputTextTool(
            context,
            reader,
            gestures,
            shell,
            subjects,
            epilogue,
            CoordSpaceResolver(prompts),
            contentAreas,
            HitReceiptBuilder(serializer),
            trace,
            prompts,
        )

    fun openAppTool(launcher: AppLauncher): OpenAppTool = OpenAppTool(reader, launcher, subjects, epilogue, prompts)

    fun restartAppTool(
        shell: ShellInput,
        launcher: AppLauncher,
    ): RestartAppTool =
        RestartAppTool(
            reader,
            shell,
            launcher,
            subjects,
            epilogue,
            RestartRulesLoader(context),
            prompts,
            context,
        )

    /** 同一个 Run 上下文（历史与计数跨调用保持）。 */
    fun ctx(): ToolContext = sharedCtx

    /** 排队一次 a11y 快照（每次 snapshot() 消费一个）。 */
    fun queue(snapshot: ScreenSnapshot) {
        reader.snapshots += Outcome.Ok(snapshot)
    }

    private fun service(): AutopAccessibilityService =
        Robolectric.buildService(AutopAccessibilityService::class.java).get()
}

/** 全屏应用窗口 + 节点的 a11y 快照。 */
internal fun a11yScreenOf(nodes: List<NodeSnapshot>): ScreenSnapshot =
    ScreenSnapshot(
        capturedAt = 0L,
        windows =
            listOf(
                WindowSnapshot(
                    windowId = 1,
                    type = WindowType.APPLICATION,
                    layer = 0,
                    packageName = "com.example.app",
                    title = "示例",
                    bounds = ENV_FULL,
                    active = true,
                    focused = true,
                    rootIdx = 0,
                ),
            ),
        nodes = nodes,
        foregroundPackage = "com.example.app",
        activity = "com.example.app.MainActivity",
        screenWidth = ENV_SCREEN_WIDTH,
        screenHeight = ENV_SCREEN_HEIGHT,
        rotation = 0,
        keyguardLocked = false,
        contentArea = ENV_FULL,
        truncated = false,
    )

/** 可点击按钮节点（带 ACTION_CLICK）。 */
internal fun clickableNode(
    idx: Int,
    text: String,
    bounds: Rect,
): NodeSnapshot =
    NodeSnapshot(
        idx = idx,
        parent = 0,
        children = emptyList(),
        childIndex = idx - 1,
        className = "android.widget.Button",
        text = text,
        bounds = bounds,
        flags = NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.CLICKABLE,
        actions = listOf(NodeActionIds.CLICK),
        packageName = "com.example.app",
    )

/** 输入框节点（默认 FOCUSED+EDITABLE；[password] 加密码标志，[editable]=false 去掉可编辑标志）。 */
internal fun editableNode(
    idx: Int,
    text: String = "",
    editable: Boolean = true,
    password: Boolean = false,
): NodeSnapshot =
    NodeSnapshot(
        idx = idx,
        parent = 0,
        children = emptyList(),
        childIndex = idx - 1,
        className = "android.widget.EditText",
        text = text,
        bounds = Rect(0, 100, ENV_SCREEN_WIDTH, 200),
        flags =
            NodeFlags.VISIBLE or NodeFlags.ENABLED or NodeFlags.FOCUSED or
                (if (editable) NodeFlags.EDITABLE else 0) or (if (password) NodeFlags.PASSWORD else 0),
        actions = emptyList(),
        packageName = "com.example.app",
    )

/** 焦点节点替身（input_text 的 focusedInput）。 */
internal class FakeResolvedNode(
    override val snapshot: NodeSnapshot,
    override val windowId: Int = 1,
    private val current: String? = null,
) : ResolvedNode {
    override fun currentText(): String? = current

    override fun performAction(
        actionId: Int,
        args: Map<String, Any?>,
    ): Boolean = true
}

/** shell 通道替身：记录 forceStop，可脚本化连接状态。 */
internal class FakeShell : ShellInput {
    override val state: MutableStateFlow<ShellState> = MutableStateFlow(ShellState.Connected)
    override val paired: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val forceStopped = mutableListOf<String>()
    var forceStopOk = true

    override suspend fun pair(
        pairingPort: Int,
        pairCode: String,
    ): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun connect(): Boolean = true

    override suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): Boolean = true

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean = true

    override suspend fun keyEvent(code: Int): Boolean = true

    override suspend fun forceStop(packageName: String): Boolean {
        forceStopped += packageName
        return forceStopOk
    }

    override suspend fun keyCombination(vararg codes: Int): Boolean = true
}

/** 应用启动替身：记录启动的包名。 */
internal class FakeAppLauncher(
    var apps: List<AppInfo> = emptyList(),
    var launchOk: Boolean = true,
) : AppLauncher {
    val launched = mutableListOf<String>()

    override suspend fun listApps(query: String?): List<AppInfo> = apps

    override suspend fun launch(packageName: String): Boolean {
        launched += packageName
        return launchOk
    }
}

/** 手势替身：记录 tap/swipe/节点动作调用；[pathOutcome] 可脚本化 performAtPath 结果。 */
internal class FakeGestures : GestureController {
    val taps = mutableListOf<Pair<Int, Int>>()
    val swipes = mutableListOf<List<Int>>()
    val nodeActions = mutableListOf<Int>()
    val nodeActionCalls = mutableListOf<Pair<Int, Map<String, Any?>>>()
    val shellKeys = mutableListOf<Int>()
    var tapOk = true
    var nodeActionOk = true
    var shellKeysOk = true

    /** focusedInput() 返回的焦点节点（null = 没有焦点）。 */
    var focused: ResolvedNode? = null
    var pathOutcome: NodeActionOutcome = NodeActionOutcome.DONE

    override suspend fun performNodeAction(
        target: ResolvedNode,
        actionId: Int,
        args: Map<String, Any?>,
    ): Boolean {
        nodeActions += actionId
        nodeActionCalls += actionId to args
        return nodeActionOk
    }

    override suspend fun performAtPath(
        windowId: Int,
        path: List<Int>,
        expectedBounds: Rect,
        actionId: Int,
    ): NodeActionOutcome {
        nodeActions += actionId
        return pathOutcome
    }

    override suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long,
    ): InjectionResult {
        taps += x to y
        return InjectionResult.accessibility(tapOk, ShellSkip.NOT_CONNECTED)
    }

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
        jitterPx: Int,
    ): InjectionResult {
        swipes += listOf(fromX, fromY, toX, toY)
        return InjectionResult.accessibility(true, ShellSkip.NOT_CONNECTED)
    }

    override suspend fun focusedInput(): ResolvedNode? = focused

    override suspend fun globalAction(action: GlobalAction): InjectionResult =
        InjectionResult.accessibility(true, ShellSkip.NOT_CONNECTED)

    override suspend fun shellKeyEvent(code: Int): Boolean {
        shellKeys += code
        return shellKeysOk
    }

    override suspend fun shellKeyCombination(codes: List<Int>): Boolean {
        shellKeys += codes
        return shellKeysOk
    }
}

/** 读屏替身：排队消费快照；截图可切换“静止/变化”帧（帧签名移动判断用）。 */
internal class FakeReader : ScreenReader {
    val snapshots = ArrayDeque<Outcome<ScreenSnapshot>>()
    var animate = false

    private var tick = 0

    override suspend fun snapshot(): Outcome<ScreenSnapshot> {
        check(snapshots.isNotEmpty()) { "no snapshot queued" }
        return snapshots.removeFirst()
    }

    override suspend fun screenshot(): Outcome<Screenshot> {
        val color = if (animate && tick % 2 == 1) 0x80 else 0xC0
        tick += 1
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(color, color, color))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return Outcome.Ok(Screenshot(width = 64, height = 64, jpegBytes = out.toByteArray(), handle = Any()))
    }
}

private class FakeVisual : VisualScreenParser {
    override val available: Boolean = true

    override suspend fun synthesize(frame: VisualFrame): VisualTree? = null

    override suspend fun warmUp() = Unit
}

private object FakeStabilizer : UiStabilizer {
    override suspend fun awaitStable(
        quietMs: Long,
        maxWaitMs: Long,
        firstEventTimeoutMs: Long,
        sampleFingerprint: suspend () -> String?,
    ): StableWait = StableWait(0L, settled = true)
}

private object FakeMonitor : UserActivityMonitor {
    override val userTouches: SharedFlow<Long> = MutableSharedFlow()

    override fun lastUserTouchAt(): Long? = null

    override fun markSelfActionWindow(
        startMs: Long,
        endMs: Long,
    ) = Unit
}

private object FakeProbe : ReplayStateProbe {
    override suspend fun sample(
        observeMode: String,
        config: ConfigSnapshot,
    ): Outcome<ReplayStateSample> =
        Outcome.Ok(
            ReplayStateSample(
                packageName = "com.example.app",
                activity = "com.example.app.MainActivity",
                fingerprint = "fp",
                keys = emptyList(),
                observeMode = "a11y",
            ),
        )
}

private object NoRedaction : WindowRedactor {
    override fun redact(
        window: WindowSnapshot,
        config: ConfigSnapshot,
    ): RedactionMode = RedactionMode.NONE
}

private class TestContext(
    private val provider: FakeConfigProvider,
) : ToolContext {
    override val runId: RunId = RunId("run-1")
    override val threadId: ThreadId = ThreadId("thread-1")
    override val config: ConfigSnapshot get() = provider.current()
    override val runScope: RunScopedValues = MapRunScope()
    override val threadStore: ThreadStore = UnusedThreadStore

    override suspend fun askUser(
        question: String,
        options: List<String>,
        timeoutMs: Long,
    ): Outcome<String> = Outcome.Err(AppError("test.unused", "askUser is not used"))

    override fun emitProgress(text: String) = Unit
}

private class MapRunScope : RunScopedValues {
    private val values = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrPut(
        key: String,
        create: () -> T,
    ): T = values.getOrPut(key) { create() } as T
}

private object UnusedThreadStore : ThreadStore {
    override suspend fun createThread(
        title: String?,
        parent: Pair<ThreadId, Long>?,
    ): ThreadId = unused()

    override suspend fun append(
        threadId: ThreadId,
        runId: RunId?,
        payload: EventPayload,
    ): ThreadEvent = unused()

    override suspend fun events(
        threadId: ThreadId,
        fromSeq: Long,
        toSeq: Long,
    ): List<ThreadEvent> = unused()

    override suspend fun eventsSinceLatestBaseline(threadId: ThreadId): List<ThreadEvent> = unused()

    override fun observe(threadId: ThreadId): Flow<ThreadEvent> = emptyFlow()

    override fun observeChanges(): Flow<Unit> = emptyFlow()

    override suspend fun saveBlob(
        threadId: ThreadId,
        name: String,
        bytes: ByteArray,
    ): String = unused()

    override suspend fun listThreads(
        offset: Int,
        limit: Int,
    ): List<ThreadSummary> = unused()

    override suspend fun rename(
        threadId: ThreadId,
        title: String,
    ) = unused()

    override suspend fun modelProfileOf(threadId: ThreadId): String? = null

    override suspend fun setModelProfile(
        threadId: ThreadId,
        profileId: String?,
    ) = Unit

    override suspend fun titleOf(threadId: ThreadId): String? = unused()

    override suspend fun delete(threadId: ThreadId) = unused()

    override suspend fun forkablePoints(threadId: ThreadId): List<Long> = unused()

    private fun unused(): Nothing = error("ThreadStore is not used in this test")
}
