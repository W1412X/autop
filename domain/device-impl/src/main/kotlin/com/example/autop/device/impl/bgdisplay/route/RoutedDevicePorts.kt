package com.example.autop.device.impl.bgdisplay.route

import android.util.Base64
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autop.core.common.AppError
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.Clock
import com.example.autop.core.common.Outcome
import com.example.autop.core.common.flatMap
import com.example.autop.core.common.map
import com.example.autop.core.common.runCatchingCancellable
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.AppInfo
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.DisplayTarget
import com.example.autop.device.GestureController
import com.example.autop.device.GlobalAction
import com.example.autop.device.InjectionChannel
import com.example.autop.device.InjectionResult
import com.example.autop.device.NodeActionOutcome
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ResolvedNode
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.Screenshot
import com.example.autop.device.StableWait
import com.example.autop.device.UiStabilizer
import com.example.autop.device.currentDisplayTarget
import com.example.autop.device.impl.AppLauncherImpl
import com.example.autop.device.impl.GestureControllerImpl
import com.example.autop.device.impl.ScreenReaderImpl
import com.example.autop.device.impl.UiStabilizerImpl
import com.example.autop.device.impl.bgdisplay.BackgroundDisplayManagerImpl
import com.example.autop.device.impl.bgdisplay.BackgroundDisplaySession
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 按当前协程目标路由读屏；目标丢失即失败，不读取真实屏。 */
@Singleton
public class RoutedScreenReader
    @Inject
    constructor(
        private val main: ScreenReaderImpl,
        private val manager: BackgroundDisplayManagerImpl,
    ) : ScreenReader {
        override suspend fun snapshot(): Outcome<ScreenSnapshot> =
            route({ it.snapshot() }) { session ->
                session.request(JSONObject().put("op", BackgroundDisplayWire.OP_SNAPSHOT)).flatMap { reply ->
                    decode(reply.getJSONObject("snapshot"))
                }
            }

        override suspend fun screenshot(): Outcome<Screenshot> =
            route({ it.screenshot() }) { session ->
                session.request(JSONObject().put("op", BackgroundDisplayWire.OP_CAPTURE)).map { reply ->
                    Screenshot(
                        reply.getInt("width"),
                        reply.getInt("height"),
                        Base64.decode(reply.getString("jpeg"), Base64.DEFAULT),
                        Any(),
                    )
                }
            }

        override suspend fun snapshotOrDegraded(): Outcome<ScreenSnapshot> = snapshot().recoverDegraded()

        private suspend fun <T> route(
            mainCall: suspend (ScreenReader) -> Outcome<T>,
            backgroundCall: suspend (BackgroundDisplaySession) -> Outcome<T>,
        ): Outcome<T> =
            when (val target = currentDisplayTarget()) {
                null, DisplayTarget.Main -> mainCall(main)
                is DisplayTarget.Background ->
                    when (val found = manager.session(target)) {
                        is Outcome.Err -> found
                        is Outcome.Ok -> backgroundCall(found.value)
                    }
            }

        private suspend inline fun <reified T> decode(value: JSONObject): Outcome<T> =
            runCatchingCancellable(DeviceErrorCodes.BG_RELEASED) {
                AutopJson.default.decodeFromString<T>(value.toString())
            }

        private suspend fun Outcome<ScreenSnapshot>.recoverDegraded(): Outcome<ScreenSnapshot> =
            if (this is Outcome.Ok) {
                this
            } else {
                when (val target = currentDisplayTarget()) {
                    is DisplayTarget.Background ->
                        when (val found = manager.session(target)) {
                            is Outcome.Err -> found
                            is Outcome.Ok ->
                                found.value
                                    .request(JSONObject().put("op", BackgroundDisplayWire.OP_FOREGROUND))
                                    .map { reply ->
                                        ScreenSnapshot(
                                            capturedAt = System.currentTimeMillis(),
                                            windows = emptyList(),
                                            nodes = emptyList(),
                                            foregroundPackage =
                                                reply
                                                    .optString(
                                                        "packageName",
                                                    ).takeIf { it.isNotBlank() },
                                            screenWidth = target.width,
                                            screenHeight = target.height,
                                            rotation = 0,
                                            keyguardLocked = false,
                                            contentArea = Rect(0, 0, target.width, target.height),
                                            truncated = false,
                                        )
                                    }
                        }
                    else -> this
                }
            }
    }

/** 后台节点/坐标手势只走当前显示 Host。 */
@Singleton
public class RoutedGestureController
    @Inject
    constructor(
        private val main: GestureControllerImpl,
        private val manager: BackgroundDisplayManagerImpl,
    ) : GestureController {
        override suspend fun performNodeAction(
            target: ResolvedNode,
            actionId: Int,
            args: Map<String, Any?>,
        ): Boolean {
            if (actionId != AccessibilityNodeInfo.ACTION_SET_TEXT) return false
            return when (val display = currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    when (
                        val result =
                            withSession(display) { session ->
                                val text =
                                    args[AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE]
                                        ?.toString()
                                        .orEmpty()
                                session
                                    .request(
                                        JSONObject()
                                            .put("op", BackgroundDisplayWire.OP_SET_TEXT)
                                            .put("windowId", target.windowId)
                                            .put("nodeJson", nodeJson(target.snapshot))
                                            .put("text", text),
                                    ).ok()
                            }
                    ) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> false
                    }
                else -> main.performNodeAction(target, actionId, args)
            }
        }

        override suspend fun performAtPath(
            windowId: Int,
            path: List<Int>,
            expectedBounds: Rect,
            actionId: Int,
        ): NodeActionOutcome =
            when (val display = currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    when (
                        val found =
                            withSession(display) { session ->
                                session
                                    .request(
                                        JSONObject()
                                            .put("op", BackgroundDisplayWire.OP_NODE_ACTION)
                                            .put("windowId", windowId)
                                            .put("path", org.json.JSONArray(path))
                                            .put("expectedBounds", rectJson(expectedBounds))
                                            .put("actionId", actionId),
                                    ).errorOrNull()
                            }
                    ) {
                        is Outcome.Ok ->
                            if (found.value == null) NodeActionOutcome.DONE else NodeActionOutcome.REJECTED
                        is Outcome.Err -> NodeActionOutcome.STALE
                    }
                else -> main.performAtPath(windowId, path, expectedBounds, actionId)
            }

        override suspend fun tap(
            x: Int,
            y: Int,
            durationMs: Long,
        ): InjectionResult =
            gesture(currentDisplayTarget() ?: DisplayTarget.Main, { main.tap(x, y, durationMs) }) {
                JSONObject().put("x", x).put("y", y).put("durationMs", durationMs)
            }

        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
            jitterPx: Int,
        ): InjectionResult =
            gesture(
                currentDisplayTarget() ?: DisplayTarget.Main,
                { main.swipe(fromX, fromY, toX, toY, durationMs, jitterPx) },
            ) {
                JSONObject()
                    .put("fromX", fromX)
                    .put("fromY", fromY)
                    .put("toX", toX)
                    .put("toY", toY)
                    .put("durationMs", durationMs)
            }

        override suspend fun focusedInput(): ResolvedNode? =
            when (val display = currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    when (
                        val result =
                            withSession(display) { session ->
                                session
                                    .request(
                                        JSONObject()
                                            .put("op", BackgroundDisplayWire.OP_FOCUSED_INPUT),
                                    ).okValue()
                                    ?.let { reply ->
                                        if (reply.optBoolean("found")) HostFocused(reply, session) else null
                                    }
                            }
                    ) {
                        is Outcome.Ok -> result.value?.resolved()
                        is Outcome.Err -> null
                    }
                else -> main.focusedInput()
            }

        override suspend fun globalAction(action: GlobalAction): InjectionResult =
            when (currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    if (action == GlobalAction.BACK) shellKeyEvent(KeyEvent.KEYCODE_BACK).injection() else failed()
                else -> main.globalAction(action)
            }

        override suspend fun shellKeyEvent(code: Int): Boolean =
            when (val display = currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    when (val result = withSession(display) { it.request(keyRequest(code)).ok() }) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> false
                    }
                else -> main.shellKeyEvent(code)
            }

        override suspend fun shellKeyCombination(codes: List<Int>): Boolean =
            when (val display = currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    when (
                        val result =
                            withSession(display) {
                                it
                                    .request(
                                        JSONObject()
                                            .put(
                                                "op",
                                                BackgroundDisplayWire.OP_KEY_COMBO,
                                            ).put("codes", org.json.JSONArray(codes)),
                                    ).ok()
                            }
                    ) {
                        is Outcome.Ok -> result.value
                        is Outcome.Err -> false
                    }
                else -> main.shellKeyCombination(codes)
            }

        private suspend fun gesture(
            display: DisplayTarget,
            mainCall: suspend () -> InjectionResult,
            request: () -> JSONObject,
        ): InjectionResult =
            when (display) {
                is DisplayTarget.Background ->
                    when (val result = withSession(display) { it.request(request()).ok() }) {
                        is Outcome.Ok -> result.value.injection()
                        is Outcome.Err -> failed()
                    }
                else -> mainCall()
            }

        private suspend fun <T> withSession(
            display: DisplayTarget.Background,
            block: suspend (BackgroundDisplaySession) -> T,
        ): Outcome<T> =
            when (val result = manager.session(display)) {
                is Outcome.Err -> result
                is Outcome.Ok -> Outcome.Ok(block(result.value))
            }
    }

private class HostFocused(
    private val reply: JSONObject,
    private val session: BackgroundDisplaySession,
) {
    fun resolved(): HostResolvedNode =
        HostResolvedNode(decodeNode(reply.getJSONObject("snapshot")), reply.getInt("windowId"), session)
}

private class HostResolvedNode(
    snapshot: NodeSnapshot,
    private val hostWindowId: Int,
    private val session: BackgroundDisplaySession,
) : ResolvedNode {
    override val snapshot = snapshot
    override val windowId = hostWindowId

    override fun currentText(): String? = snapshot.text

    override fun performAction(
        actionId: Int,
        args: Map<String, Any?>,
    ): Boolean =
        kotlinx.coroutines.runBlocking {
            if (actionId != AccessibilityNodeInfo.ACTION_SET_TEXT) return@runBlocking false
            session
                .request(
                    JSONObject()
                        .put("op", BackgroundDisplayWire.OP_SET_TEXT)
                        .put("windowId", windowId)
                        .put("nodeJson", nodeJson(snapshot))
                        .put(
                            "text",
                            args[AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE]
                                ?.toString()
                                .orEmpty(),
                        ),
                ).ok()
        }
}

/** 后台应用启动；应用列表是包信息读取，不操作屏幕。 */
@Singleton
public class RoutedAppLauncher
    @Inject
    constructor(
        private val main: AppLauncherImpl,
        private val manager: BackgroundDisplayManagerImpl,
        private val configProvider: ConfigProvider,
    ) : AppLauncher {
        override suspend fun listApps(query: String?): List<AppInfo> = main.listApps(query)

        override suspend fun launch(packageName: String): Boolean = launchWithError(packageName) == null

        override suspend fun launchWithError(packageName: String): AppError? =
            when (val display = currentDisplayTarget()) {
                is DisplayTarget.Background ->
                    when (val found = manager.session(display)) {
                        is Outcome.Err -> found.error
                        is Outcome.Ok ->
                            found.value
                                .request(
                                    JSONObject()
                                        .put("op", BackgroundDisplayWire.OP_LAUNCH)
                                        .put("packageName", packageName)
                                        .put(
                                            "timeoutMs",
                                            configProvider.current()[DeviceConfig.BACKGROUND].launchTimeoutMs,
                                        ),
                                ).errorOrNull()
                    }
                else -> main.launchWithError(packageName)
            }
    }

/** 后台稳定等待只比较目标屏截图指纹，不订阅真实屏无障碍事件。 */
@Singleton
public class RoutedUiStabilizer
    @Inject
    constructor(
        private val main: UiStabilizerImpl,
        private val background: BackgroundUiStabilizer,
    ) : UiStabilizer {
        override suspend fun awaitStable(
            quietMs: Long,
            maxWaitMs: Long,
            firstEventTimeoutMs: Long,
            sampleFingerprint: suspend () -> String?,
        ): StableWait =
            if (currentDisplayTarget() is DisplayTarget.Background) {
                background.awaitStable(quietMs, maxWaitMs, firstEventTimeoutMs, sampleFingerprint)
            } else {
                main.awaitStable(quietMs, maxWaitMs, firstEventTimeoutMs, sampleFingerprint)
            }
    }

@Singleton
public class BackgroundUiStabilizer
    @Inject
    constructor(
        private val clock: Clock,
    ) : UiStabilizer {
        override suspend fun awaitStable(
            quietMs: Long,
            maxWaitMs: Long,
            firstEventTimeoutMs: Long,
            sampleFingerprint: suspend () -> String?,
        ): StableWait {
            val started = clock.nowMillis()
            var baseline = sampleFingerprint()
            var changed = false
            var changedAt = started
            val eventDeadline = started + firstEventTimeoutMs
            while (clock.nowMillis() < eventDeadline) {
                delay(minOf(STABILIZER_POLL_MS, maxOf(1L, eventDeadline - clock.nowMillis())))
                val next = sampleFingerprint()
                if (next != baseline) {
                    baseline = next
                    changed = true
                    changedAt = clock.nowMillis()
                    break
                }
            }
            if (!changed) return StableWait(clock.nowMillis() - started, true)
            val deadline = started + maxWaitMs
            while (clock.nowMillis() < deadline) {
                delay(minOf(STABILIZER_POLL_MS, maxOf(1L, deadline - clock.nowMillis())))
                val now = clock.nowMillis()
                val next = sampleFingerprint()
                if (next == baseline) {
                    if (now - changedAt >= quietMs) return StableWait(now - started, true)
                } else {
                    baseline = next
                    changedAt = now
                }
            }
            return StableWait(clock.nowMillis() - started, false)
        }
    }

private const val STABILIZER_POLL_MS = 50L

private fun keyRequest(code: Int): JSONObject = JSONObject().put("op", BackgroundDisplayWire.OP_KEY).put("code", code)

private fun rectJson(rect: Rect): JSONObject =
    JSONObject()
        .put("l", rect.l)
        .put("t", rect.t)
        .put("r", rect.r)
        .put("b", rect.b)

private fun nodeJson(node: NodeSnapshot): String = AutopJson.default.encodeToString(NodeSnapshot.serializer(), node)

private fun decodeNode(value: JSONObject): NodeSnapshot =
    AutopJson.default.decodeFromString(NodeSnapshot.serializer(), value.toString())

private fun Outcome<*>.ok(): Boolean = this is Outcome.Ok

private fun <T> Outcome<T>.okValue(): T? = (this as? Outcome.Ok)?.value

private fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Err)?.error

private fun Boolean.injection(): InjectionResult =
    InjectionResult(
        this,
        if (this) InjectionChannel.BACKGROUND else InjectionChannel.NONE,
    )

private fun failed(): InjectionResult = InjectionResult.FAILED
