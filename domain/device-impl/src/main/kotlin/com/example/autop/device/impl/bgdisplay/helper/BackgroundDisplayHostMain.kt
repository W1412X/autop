@file:Suppress("TooGenericExceptionCaught")

package com.example.autop.device.impl.bgdisplay.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.util.SparseArray
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.autop.core.common.AutopJson
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.WindowSnapshot
import com.example.autop.device.WindowType
import com.example.autop.device.core.ContentArea
import com.example.autop.device.impl.NodeSnapshotReader
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_ACTION_UNSUPPORTED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_APP_ON_OTHER_DISPLAY
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_AUTOMATION_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_BAD_REQUEST
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_CAPTURE_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_DISPLAY_CREATE_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_DISPLAY_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_FOREGROUND_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_FRAME_NOT_READY
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_INJECTION_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_LAUNCH_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_LOCKED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_SET_TEXT_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_STALE_NODE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_TARGET_CHANGED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_TREE_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_UID_MISMATCH
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_UNKNOWN_OP
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_UNSUPPORTED_ANDROID
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_VERSION_MISMATCH
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.EXPECTED_UID
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_DISPLAY_DIMENSION
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_DISPLAY_PIXELS
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_GESTURE_MS
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_KEY_CODE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_LAUNCH_TIMEOUT_MS
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_NODE_JSON_BYTES
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_OP_BYTES
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_REPLY_BYTES
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_REPLY_OVERHEAD_BYTES
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_REQUEST_CHARS
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_TEXT_BYTES
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MIN_REPLY_BYTES
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_CAPTURE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_CREATE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_FOCUSED_INPUT
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_FOREGROUND
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_KEY
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_KEY_COMBO
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_LAUNCH
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_NODE_ACTION
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_RELEASE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_SET_TEXT
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_SHUTDOWN
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_SNAPSHOT
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_SWIPE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.OP_TAP
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.PROTOCOL_VERSION
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.failure
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.int
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.intArray
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.long
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.packageName
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ready
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.requestId
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.sessionId
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.stableError
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.string
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.success
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import android.graphics.Rect as AndroidRect

public object BackgroundDisplayMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        var server: BackgroundDisplayServer? = null
        try {
            if (args.singleOrNull()?.toIntOrNull() != PROTOCOL_VERSION) failStartup(ERR_VERSION_MISMATCH)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) failStartup(ERR_UNSUPPORTED_ANDROID)
            if (Process.myUid() != EXPECTED_UID) failStartup(ERR_UID_MISMATCH)
            val context = systemContext()
            server = BackgroundDisplayServer(context)
            server.emit(ready(Process.myUid()))
            val input = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
            while (!server.shutdown) {
                val line = readBoundedLine(input) ?: break
                when {
                    line == OVERSIZED_LINE -> server.emit(failure(null, ERR_BAD_REQUEST))
                    line.isNotBlank() -> server.handle(line)
                }
            }
        } catch (error: Throwable) {
            System.err.println("background-display: ${error.javaClass.simpleName}")
        } finally {
            runCatching { server?.close() }
            runCatching { System.`in`.close() }
            exitProcess(0)
        }
    }

    private fun systemContext(): Context {
        Looper.prepareMainLooper()
        val threadType = Class.forName("android.app.ActivityThread")
        val thread = threadType.getMethod("systemMain").invoke(null)
        val system = threadType.getMethod("getSystemContext").invoke(thread) as Context
        return system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun failStartup(code: String): Nothing {
        System.err.println("background-display: $code")
        runCatching { System.`in`.close() }
        exitProcess(0)
    }

    private fun readBoundedLine(input: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code || value == '\r'.code) return line.toString()
            if (line.length == MAX_REQUEST_CHARS) {
                input.discardLine()
                return OVERSIZED_LINE
            }
            line.append(value.toChar())
        }
    }

    private fun BufferedReader.discardLine() {
        while (true) {
            val value = read()
            if (value < 0 || value == '\n'.code || value == '\r'.code) return
        }
    }
}

private const val OVERSIZED_LINE = "\u0000"
private const val MAX_SNAPSHOT_NODES = 1200
private const val MAX_TREE_DEPTH = 50
private const val MAX_NODE_CHILDREN = 256
private const val WINDOW_RETRY_COUNT = 20
private const val WINDOW_RETRY_MS = 100L
private const val FOCUS_RETRY_COUNT = 10
private const val FOCUS_RETRY_MS = 100L
private const val IMAGE_READER_MAX_IMAGES = 2
private const val CAPTURE_POLL_MS = 40L
private const val LAUNCH_POLL_MS = 120L
private const val INPUT_SETTLE_MS = 120L
private const val AUTOMATION_TIMEOUT_MS = 3000L
private const val AUTOMATION_OPERATION_TIMEOUT_MS = 30_000L
private const val HOST_CLOSE_TIMEOUT_SECONDS = 10L
private const val SWIPE_STEP_PX = 48.0
private const val SWIPE_MAX_STEPS = 40
private val DISPLAY_NODE_ACTIONS =
    setOf(
        AccessibilityNodeInfo.ACTION_CLICK,
        AccessibilityNodeInfo.ACTION_LONG_CLICK,
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
    )

private data class DisplaySpec(
    val id: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val jpegQuality: Int,
    val maxPayloadBytes: Int,
)

private class DisplaySession(
    val id: String,
    val display: Display,
    val virtualDisplay: VirtualDisplay,
    val frames: BackgroundFrames,
    val width: Int,
    val height: Int,
    val jpegQuality: Int,
    val maxPayloadBytes: Int,
) {
    val placement = BackgroundDisplayPlacement()
}

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class BackgroundDisplayServer(
    private val context: Context,
) {
    private val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8.name())
    private val sessions = ConcurrentHashMap<String, DisplaySession>()
    private val launchLock = Any()
    private val executor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "autop-bg-request").apply { isDaemon = true }
        }
    private var automationSession: AutomationSession? = null

    @Volatile
    private var closing = false

    @Volatile
    var shutdown = false
        private set

    fun emit(value: JSONObject) {
        out.println(value.toString())
    }

    fun handle(line: String) {
        executor.execute { process(line) }
    }

    private fun process(line: String) {
        val request = runCatching { JSONObject(line) }.getOrNull()
        if (request == null) {
            emit(failure(null, ERR_BAD_REQUEST))
            return
        }
        val id =
            try {
                requestId(request)
            } catch (_: IllegalArgumentException) {
                emit(failure(null, ERR_BAD_REQUEST))
                return
            }
        val reply =
            try {
                val data = route(string(request, "op", MAX_OP_BYTES), request)
                success(id).also { destination -> data.keys().forEach { key -> destination.put(key, data.opt(key)) } }
            } catch (error: Throwable) {
                if (!error.isExpectedProtocolFailure()) {
                    System.err.println("background-display request: ${error.javaClass.simpleName}")
                }
                failure(id, stableError(error))
            }
        emit(reply)
        if (reply.optBoolean("exit")) runCatching { System.`in`.close() }
    }

    fun close() {
        closing = true
        executor.shutdown()
        val drained = runCatching { executor.awaitTermination(HOST_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (drained.getOrDefault(false).not()) {
            executor.shutdownNow()
            return // Process exit revokes the display binder leases even if a framework call is stuck.
        }
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { runCatching { releaseSession(it) } }
        runCatching { automationSession?.close() }
        automationSession = null
    }

    private val requestHandlers =
        mapOf<String, (JSONObject) -> JSONObject>(
            OP_LAUNCH to ::launch,
            OP_TAP to ::tap,
            OP_SWIPE to ::swipe,
            OP_KEY to ::key,
            OP_KEY_COMBO to { hostFailure(ERR_ACTION_UNSUPPORTED) },
            OP_SET_TEXT to { request -> automation().withAutomation { setText(request) } },
            OP_NODE_ACTION to { request -> automation().withAutomation { nodeAction(request) } },
        )

    private val readHandlers =
        mapOf<String, (JSONObject) -> JSONObject>(
            OP_SNAPSHOT to
                { request ->
                    automation().withAutomation { JSONObject().put("snapshot", snapshotJson(snapshot(request))) }
                },
            OP_CAPTURE to ::capture,
            OP_FOCUSED_INPUT to { request -> automation().withAutomation { focusedInput(request) } },
            OP_FOREGROUND to ::foreground,
        )

    private fun route(
        op: String,
        request: JSONObject,
    ): JSONObject =
        when (op) {
            OP_CREATE -> create(request)
            OP_RELEASE -> release(request)
            OP_SHUTDOWN -> {
                closing = true
                shutdown = true
                JSONObject().put("exit", true)
            }
            else -> displayOperation(op, request)
        }

    private fun displayOperation(
        op: String,
        request: JSONObject,
    ): JSONObject {
        val action = requestHandlers[op]
        val handler = action ?: readHandlers[op] ?: hostFailure(ERR_UNKNOWN_OP)
        val session = requireSession(request)
        return synchronized(session) {
            requireSession(request)
            val before = verifyPlacement(session)
            if (action != null && op != OP_LAUNCH && before.virtual == null) hostFailure(ERR_INJECTION_FAILED)
            try {
                val result = handler(request)
                requirePayloadSize(op, result, session.maxPayloadBytes)
                if (action != null && op != OP_LAUNCH) SystemClock.sleep(INPUT_SETTLE_MS)
                result
            } finally {
                if (action != null && op != OP_LAUNCH) verifyPlacement(session, before)
            }
        }
    }

    private fun requirePayloadSize(
        op: String,
        result: JSONObject,
        limit: Int,
    ) {
        val error =
            when (op) {
                OP_SNAPSHOT, OP_FOCUSED_INPUT -> ERR_TREE_UNAVAILABLE
                OP_CAPTURE -> ERR_CAPTURE_FAILED
                else -> return
            }
        // Leave room for the request ID and outer response fields.
        if (result.toString().toByteArray(Charsets.UTF_8).size > limit - MAX_REPLY_OVERHEAD_BYTES) {
            hostFailure(error)
        }
    }

    private data class Foreground(
        val virtual: BackgroundDisplayPlacement.Task?,
        val primary: BackgroundDisplayPlacement.Task?,
    )

    private fun verifyPlacement(
        session: DisplaySession,
        before: Foreground? = null,
    ): Foreground {
        if (session.placement.failed) hostFailure(ERR_TARGET_CHANGED)
        ensureUnlocked()
        val (virtual, other) = allRootTasks().partition { it.displayId == session.display.displayId }
        val foreground =
            Foreground(
                virtual.firstOrNull { it.visible && it.topActivity != null },
                other.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY && it.visible && it.topActivity != null },
            )
        val safe =
            session.placement.observe(
                virtual,
                other,
                before?.primary,
                foreground.primary,
                afterWrite = before != null,
                now = SystemClock.uptimeMillis(),
            )
        if (!safe) hostFailure(ERR_TARGET_CHANGED)
        return foreground
    }

    private fun ensureUnlocked() {
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) hostFailure(ERR_LOCKED)
    }

    private fun create(request: JSONObject): JSONObject {
        if (closing) hostFailure(ERR_DISPLAY_CREATE_FAILED)
        val spec = displaySpec(request)
        if (sessions.containsKey(spec.id)) hostFailure(ERR_DISPLAY_CREATE_FAILED)
        var frames: BackgroundFrames? = null
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        try {
            val initialReader =
                ImageReader.newInstance(
                    spec.width,
                    spec.height,
                    android.graphics.PixelFormat.RGBA_8888,
                    IMAGE_READER_MAX_IMAGES,
                )
            reader = initialReader
            val createdDisplay = openVirtualDisplay(spec.id, spec.width, spec.height, spec.dpi, initialReader)
            display = createdDisplay
            val createdFrames = BackgroundFrames(createdDisplay, spec.width, spec.height, initialReader)
            frames = createdFrames
            val target = checkNotNull(createdDisplay.display) { "display id" }
            check(target.displayId > Display.DEFAULT_DISPLAY && target.isValid) { "virtual display id" }
            setImePolicyHide(target.displayId)
            automation()
            val session =
                DisplaySession(
                    id = spec.id,
                    display = target,
                    virtualDisplay = createdDisplay,
                    frames = createdFrames,
                    width = spec.width,
                    height = spec.height,
                    jpegQuality = spec.jpegQuality,
                    maxPayloadBytes = spec.maxPayloadBytes,
                )
            check(
                !closing && sessions.putIfAbsent(spec.id, session) == null,
            ) { "display session already closed or created" }
            reader = null
            return JSONObject(
                mapOf(
                    "displayId" to target.displayId,
                    "width" to spec.width,
                    "height" to spec.height,
                    "dpi" to spec.dpi,
                ),
            )
        } catch (error: Throwable) {
            Log.e("BackgroundDisplayHost", "display creation failed", error)
            runCatching { display?.release() }
            runCatching { frames?.close() }
            runCatching { reader?.close() }
            when (error) {
                is HostFailure -> raise(error)
                else -> hostFailure(ERR_DISPLAY_CREATE_FAILED, error)
            }
        }
    }

    private fun displaySpec(request: JSONObject): DisplaySpec {
        val spec =
            DisplaySpec(
                id = sessionId(request),
                width = int(request, "width", min = 1, max = MAX_DISPLAY_DIMENSION),
                height = int(request, "height", min = 1, max = MAX_DISPLAY_DIMENSION),
                dpi = int(request, "dpi", min = 72, max = 640),
                jpegQuality = int(request, "jpegQuality", min = 1, max = 100),
                maxPayloadBytes = int(request, "maxPayloadBytes", min = MIN_REPLY_BYTES, max = MAX_REPLY_BYTES),
            )
        require(spec.width.toLong() * spec.height <= MAX_DISPLAY_PIXELS) { "display size" }
        return spec
    }

    private fun openVirtualDisplay(
        id: String,
        width: Int,
        height: Int,
        dpi: Int,
        reader: ImageReader,
    ): VirtualDisplay {
        val displays = checkNotNull(context.getSystemService(DisplayManager::class.java)) { "display manager" }
        return checkNotNull(
            displays.createVirtualDisplay(
                "autop-bg-${Process.myPid()}-$id",
                width,
                height,
                dpi,
                reader.surface,
                virtualDisplayFlags(),
                null,
                null,
            ),
        ) { "virtual display" }
    }

    @Synchronized
    private fun automation(): AutomationSession {
        automationSession?.let { return it }
        val opened = AutomationSession(-1)
        try {
            opened.open()
        } catch (error: Throwable) {
            runCatching { opened.close() }
            throw error
        }
        automationSession = opened
        return opened
    }

    private fun virtualDisplayFlags(): Int =
        listOf(
            "PUBLIC",
            "OWN_CONTENT_ONLY",
            "SUPPORTS_TOUCH",
            "DESTROY_CONTENT_ON_REMOVAL",
            "TRUSTED",
            "OWN_FOCUS",
            "STEAL_TOP_FOCUS_DISABLED",
        ).fold(0) { flags, name ->
            flags or DisplayManager::class.java.getDeclaredField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null)
        }

    private fun setImePolicyHide(displayId: Int) {
        val binder =
            checkNotNull(
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "window") as? IBinder,
            ) { "window service" }
        val manager =
            Class
                .forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        Class
            .forName("android.view.IWindowManager")
            .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, displayId, 2)
    }

    private fun launch(request: JSONObject): JSONObject =
        synchronized(launchLock) {
            launchLocked(request)
        }

    private fun launchLocked(request: JSONObject): JSONObject {
        val packageName = packageName(request)
        val timeoutMs = long(request, "timeoutMs", min = 1L, max = MAX_LAUNCH_TIMEOUT_MS)
        val session = requireSession(request)
        val display = session.display
        val displayContext = context.createDisplayContext(display)
        val intent =
            displayContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: hostFailure(ERR_LAUNCH_FAILED)
        if (intent.component?.packageName != packageName) hostFailure(ERR_LAUNCH_FAILED)
        if (allRootTasks().any {
                it.displayId != display.displayId &&
                    it.topActivity != null &&
                    packageName in it.packages
            }
        ) {
            hostFailure(ERR_APP_ON_OTHER_DISPLAY)
        }
        if (foregroundPackage(display.displayId) == packageName) return JSONObject()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager?.isActivityStartAllowedOnDisplay(displayContext, display.displayId, intent) != true) {
            hostFailure(ERR_LAUNCH_FAILED)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(display.displayId) }
        val pending =
            PendingIntent.getActivity(
                displayContext,
                display.displayId,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
        try {
            pending.send(displayContext, 0, null, null, null, null, options.toBundle())
        } catch (error: Throwable) {
            session.placement.invalidate()
            hostFailure(ERR_LAUNCH_FAILED, error)
        } finally {
            runCatching { pending.cancel() }
        }
        session.placement.watchPackage(packageName)
        try {
            awaitLaunch(request, session, packageName, timeoutMs)
        } catch (error: Throwable) {
            session.placement.invalidate()
            throw error
        }
        return JSONObject()
    }

    private fun awaitLaunch(
        request: JSONObject,
        session: DisplaySession,
        packageName: String,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            requireSession(request)
            val foreground = verifyPlacement(session)
            if (foreground.virtual?.topPackage == packageName) return
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) hostFailure(ERR_TARGET_CHANGED)
            SystemClock.sleep(minOf(LAUNCH_POLL_MS, remaining))
        }
    }

    private fun snapshot(request: JSONObject): ScreenSnapshot {
        val session = requireSession(request)
        val display = session.display
        val state = CaptureState()
        val windows = targetWindows(display.displayId).map { window -> windowSnapshotOf(window, state) }
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val screenWidth = if (metrics.widthPixels > 0) metrics.widthPixels else session.width
        val screenHeight = if (metrics.heightPixels > 0) metrics.heightPixels else session.height
        val foreground =
            windows.firstOrNull { it.type == WindowType.APPLICATION && it.active && !it.packageName.isNullOrBlank() }
                ?: windows
                    .filter { it.type == WindowType.APPLICATION && !it.packageName.isNullOrBlank() }
                    .maxByOrNull { it.layer }
        return ScreenSnapshot(
            capturedAt = System.currentTimeMillis(),
            windows = windows,
            nodes = state.nodes,
            foregroundPackage = foreground?.packageName,
            activity = null,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rotation = display.rotation,
            keyguardLocked =
                context
                    .createDisplayContext(display)
                    .getSystemService(KeyguardManager::class.java)
                    ?.isKeyguardLocked == true,
            contentArea = ContentArea.of(screenWidth, screenHeight, windows),
            truncated = state.truncated,
        )
    }

    private fun windowSnapshotOf(
        window: AccessibilityWindowInfo,
        state: CaptureState,
    ): WindowSnapshot {
        val root = window.root
        val rootIdx = if (root != null && !state.truncated) state.nodes.size else null
        if (root != null && !state.truncated) collectNode(root, null, 0, 0, state)
        val bounds = AndroidRect()
        window.getBoundsInScreen(bounds)
        return WindowSnapshot(
            windowId = window.id,
            type = windowType(window.type),
            layer = window.layer,
            packageName = root?.packageName?.toString(),
            title = window.title?.toString(),
            bounds = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            active = window.isActive,
            focused = window.isFocused,
            rootIdx = rootIdx,
        )
    }

    private fun collectNode(
        node: AccessibilityNodeInfo,
        parent: Int?,
        childIndex: Int,
        depth: Int,
        state: CaptureState,
    ) {
        if (state.truncated) return
        if (depth > MAX_TREE_DEPTH) {
            state.truncated = true
            return
        }
        node.refresh()
        val idx = state.nodes.size
        state.nodes += NodeSnapshotReader.read(node, idx, parent, childIndex)
        if (state.nodes.size >= MAX_SNAPSHOT_NODES) {
            state.truncated = true
            return
        }
        val children = mutableListOf<Int>()
        var index = 0
        while (index < node.childCount && !state.truncated) {
            if (index >= MAX_NODE_CHILDREN || depth == MAX_TREE_DEPTH) {
                state.truncated = true
                break
            }
            val child = node.getChild(index)
            if (child != null) {
                val childIdx = state.nodes.size
                collectNode(child, idx, index, depth + 1, state)
                if (state.nodes.size > childIdx) children += childIdx
            }
            index++
        }
        state.nodes[idx] = state.nodes[idx].copy(children = children)
    }

    private fun targetWindows(displayId: Int): List<AccessibilityWindowInfo> =
        automation().withAutomation {
            val automation = checkNotNull(this@BackgroundDisplayServer.automationSession?.automation)
            try {
                automation.clearCache()
                repeat(WINDOW_RETRY_COUNT) {
                    val all = UiAutomation::class.java.getMethod("getWindowsOnAllDisplays").invoke(automation)
                    val target = (all as? SparseArray<*>)?.get(displayId)
                    if (all !is SparseArray<*>) hostFailure(ERR_TREE_UNAVAILABLE)
                    val windows = (target as? List<*>)?.filterIsInstance<AccessibilityWindowInfo>().orEmpty()
                    if (windows.any { it.displayId != displayId }) hostFailure(ERR_TREE_UNAVAILABLE)
                    if (windows.isNotEmpty()) return@withAutomation windows.sortedByDescending { it.layer }
                    SystemClock.sleep(WINDOW_RETRY_MS)
                }
            } catch (error: Throwable) {
                when (error) {
                    is HostFailure -> raise(error)
                    else -> hostFailure(ERR_TREE_UNAVAILABLE, error)
                }
            }
            hostFailure(ERR_TREE_UNAVAILABLE)
        }

    private fun capture(request: JSONObject): JSONObject {
        val session = requireSession(request)
        try {
            val reader = session.frames.rotateForCapture()
            val deadline = SystemClock.uptimeMillis() + BackgroundFrameReadiness.CAPTURE_TIMEOUT_MS
            for (attempt in 0..1) {
                requireSession(request)
                val captured = captureFrame(request, reader, session, attempt, deadline)
                if (captured != null) return captured
                SystemClock.sleep(BackgroundFrameReadiness.retryWaitMs(deadline - SystemClock.uptimeMillis()))
            }
            hostFailure(ERR_FRAME_NOT_READY)
        } catch (error: Throwable) {
            when (error) {
                is HostFailure -> raise(error)
                else -> hostFailure(ERR_CAPTURE_FAILED, error)
            }
        }
    }

    private fun captureFrame(
        request: JSONObject,
        reader: ImageReader,
        session: DisplaySession,
        attempt: Int,
        deadline: Long,
    ): JSONObject? {
        if (Thread.currentThread().isInterrupted) hostFailure(ERR_CAPTURE_FAILED)
        if (attempt > 0 && SystemClock.uptimeMillis() >= deadline) hostFailure(ERR_FRAME_NOT_READY)
        val image = acquireFrame(request, reader, deadline)
        var bitmap: Bitmap? = null
        try {
            bitmap = bitmapOf(image, session.width, session.height)
            val next =
                BackgroundFrameReadiness.next(
                    bitmap.isMostlyBlack(),
                    attempt,
                    deadline - SystemClock.uptimeMillis(),
                )
            if (next == BackgroundFrameReadiness.Next.NOT_READY) hostFailure(ERR_FRAME_NOT_READY)
            if (next != BackgroundFrameReadiness.Next.READY) return null
            return JSONObject()
                .put(
                    "jpeg",
                    Base64.encodeToString(encodeJpeg(bitmap, session.jpegQuality), Base64.NO_WRAP),
                ).put("width", session.width)
                .put("height", session.height)
        } finally {
            image.close()
            bitmap?.recycle()
        }
    }

    private fun acquireFrame(
        request: JSONObject,
        reader: ImageReader,
        deadline: Long,
    ): Image {
        var image = reader.acquireLatestImage()
        while (image == null) {
            requireSession(request)
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) hostFailure(ERR_FRAME_NOT_READY)
            SystemClock.sleep(minOf(CAPTURE_POLL_MS, remaining))
            image = reader.acquireLatestImage()
        }
        return image
    }

    private fun bitmapOf(
        image: Image,
        width: Int,
        height: Int,
    ): Bitmap {
        require(image.width == width && image.height == height) { "capture dimensions changed" }
        val plane = image.planes.singleOrNull() ?: hostFailure(ERR_CAPTURE_FAILED)
        if (plane.pixelStride != Int.SIZE_BYTES || plane.rowStride < width * Int.SIZE_BYTES) {
            hostFailure(ERR_CAPTURE_FAILED)
        }
        val pixels = ByteBuffer.allocate(width * height * Int.SIZE_BYTES)
        val source = plane.buffer.duplicate()
        for (row in 0 until height) {
            val start = row * plane.rowStride
            source.limit(start + width * Int.SIZE_BYTES)
            source.position(start)
            pixels.put(source)
        }
        pixels.flip()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.copyPixelsFromBuffer(pixels)
        }
    }

    private fun encodeJpeg(
        bitmap: Bitmap,
        quality: Int,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "jpeg encode" }
            output.toByteArray()
        }

    private fun tap(request: JSONObject): JSONObject {
        val x = int(request, "x")
        val y = int(request, "y")
        return touch(request, x, y, x, y, steps = 1)
    }

    private fun swipe(request: JSONObject): JSONObject {
        val fromX = int(request, "fromX")
        val fromY = int(request, "fromY")
        val toX = int(request, "toX")
        val toY = int(request, "toY")
        val distance = maxOf(kotlin.math.abs(fromX.toLong() - toX), kotlin.math.abs(fromY.toLong() - toY))
        val steps =
            kotlin.math
                .ceil(distance / SWIPE_STEP_PX)
                .toInt()
                .coerceIn(1, SWIPE_MAX_STEPS)
        return touch(request, fromX, fromY, toX, toY, steps)
    }

    private fun touch(
        request: JSONObject,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        steps: Int,
    ): JSONObject {
        val duration = long(request, "durationMs", min = 0L, max = MAX_GESTURE_MS)
        val session = requireSession(request)
        validatePoint(session, fromX, fromY)
        validatePoint(session, toX, toY)
        val manager = inputManager()
        val downTime = SystemClock.uptimeMillis()
        var x = fromX
        var y = fromY

        fun send(action: Int) {
            val event =
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                setDisplayId(event, session.display.displayId)
                if (!inject(manager, event)) hostFailure(ERR_INJECTION_FAILED)
            } finally {
                event.recycle()
            }
        }

        try {
            send(MotionEvent.ACTION_DOWN)
            for (step in 1..steps) {
                waitForGestureStep(request, downTime + duration * step / steps)
                x = fromX + (toX - fromX) * step / steps
                y = fromY + (toY - fromY) * step / steps
                if (step < steps) send(MotionEvent.ACTION_MOVE)
            }
            requireSession(request)
            ensureUnlocked()
            send(MotionEvent.ACTION_UP)
        } catch (error: Throwable) {
            // A timed-out dispatch may have reached the receiver. Cancel instead of replaying or clicking on release.
            if (session.display.isValid) runCatching { send(MotionEvent.ACTION_CANCEL) }
            throw error
        }
        return JSONObject()
    }

    private fun waitForGestureStep(
        request: JSONObject,
        deadline: Long,
    ) {
        while (true) {
            requireSession(request)
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) return
            SystemClock.sleep(minOf(LAUNCH_POLL_MS, remaining))
        }
    }

    private fun key(request: JSONObject): JSONObject {
        val code = int(request, "code", min = 1, max = MAX_KEY_CODE)
        if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_ENTER) hostFailure(ERR_ACTION_UNSUPPORTED)
        val session = requireSession(request)
        val manager = inputManager()
        val downTime = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            flags: Int = 0,
        ) {
            val event =
                KeyEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    code,
                    0,
                    0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    flags,
                    InputDevice.SOURCE_KEYBOARD,
                )
            setDisplayId(event, session.display.displayId)
            if (!inject(manager, event)) hostFailure(ERR_INJECTION_FAILED)
        }
        try {
            send(KeyEvent.ACTION_DOWN)
            requireSession(request)
            ensureUnlocked()
            send(KeyEvent.ACTION_UP)
        } catch (error: Throwable) {
            if (session.display.isValid) runCatching { send(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED) }
            throw error
        }
        return JSONObject()
    }

    private fun focusedInput(request: JSONObject): JSONObject {
        val displayId = requireSession(request).display.displayId
        repeat(FOCUS_RETRY_COUNT) {
            val found = targetWindows(displayId).firstNotNullOfOrNull(::focusedInputSnapshot)
            if (found != null) {
                return JSONObject()
                    .put("found", true)
                    .put("snapshot", nodeJson(found.first))
                    .put("windowId", found.second)
            }
            SystemClock.sleep(FOCUS_RETRY_MS)
        }
        return JSONObject().put("found", false)
    }

    private fun focusedInputSnapshot(window: AccessibilityWindowInfo): Pair<NodeSnapshot, Int>? {
        val root = window.root ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val node =
            focused?.takeIf { it.isEditable && it.isVisibleToUser }
                ?: findFocusedEditable(root, 0, IntArray(1))
                ?: return null
        if (node.windowId != window.id || node.window?.displayId != window.displayId) return null
        return NodeSnapshotReader.read(node, 0, null, 0) to window.id
    }

    private fun findFocusedEditable(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IntArray,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0]++ >= MAX_SNAPSHOT_NODES) return null
        node.refresh()
        if (node.isEditable && node.isFocused && node.isVisibleToUser) return node
        for (index in 0 until minOf(node.childCount, MAX_NODE_CHILDREN)) {
            val child = node.getChild(index) ?: continue
            findFocusedEditable(child, depth + 1, visited)?.let { return it }
        }
        return null
    }

    private fun nodeAction(request: JSONObject): JSONObject {
        val displayId = requireSession(request).display.displayId
        val windowId = int(request, "windowId")
        val path = intArray(request, "path", maxSize = MAX_TREE_DEPTH, min = 0)
        require(path.all { it < MAX_NODE_CHILDREN }) { "path" }
        val actionId = int(request, "actionId", min = 1)
        if (actionId !in DISPLAY_NODE_ACTIONS) hostFailure(ERR_ACTION_UNSUPPORTED)
        val expected =
            request.opt("expectedBounds") as? JSONObject
                ?: throw IllegalArgumentException("expectedBounds")
        val left = int(expected, "l")
        val top = int(expected, "t")
        val right = int(expected, "r")
        val bottom = int(expected, "b")
        val window =
            targetWindows(displayId).firstOrNull { it.id == windowId }
                ?: hostFailure(ERR_STALE_NODE)
        var node = window.root ?: hostFailure(ERR_STALE_NODE)
        for (childIndex in path) {
            val child = node.getChild(childIndex) ?: hostFailure(ERR_STALE_NODE)
            node = child
        }
        requireLiveNode(request, node, displayId, windowId)
        val bounds = AndroidRect()
        node.getBoundsInScreen(bounds)
        if (bounds != AndroidRect(left, top, right, bottom)) hostFailure(ERR_STALE_NODE)
        if (!node.performAction(actionId, null)) hostFailure(ERR_INJECTION_FAILED)
        return JSONObject()
    }

    private fun setText(request: JSONObject): JSONObject {
        val displayId = requireSession(request).display.displayId
        val nodeText = string(request, "nodeJson", MAX_NODE_JSON_BYTES)
        val windowId = int(request, "windowId")
        val text = string(request, "text", MAX_TEXT_BYTES)
        val target =
            runCatching { AutopJson.default.decodeFromString(NodeSnapshot.serializer(), nodeText) }.getOrNull()
                ?: throw IllegalArgumentException("nodeJson")
        if (target.flags and NodeFlags.EDITABLE == 0 || target.flags and NodeFlags.PASSWORD != 0) {
            hostFailure(ERR_STALE_NODE)
        }
        val root = targetWindows(displayId).firstOrNull { it.id == windowId }?.root ?: hostFailure(ERR_STALE_NODE)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        findEditableMatches(root, target, windowId, 0, IntArray(1), matches)
        val node = matches.singleOrNull() ?: hostFailure(ERR_STALE_NODE)
        requireLiveNode(request, node, displayId, windowId)
        if (node.isPassword || !node.isEditable || !matchesTarget(node, target, windowId)) hostFailure(ERR_STALE_NODE)
        val arguments =
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            hostFailure(ERR_SET_TEXT_FAILED)
        }
        return JSONObject()
    }

    private fun requireLiveNode(
        request: JSONObject,
        node: AccessibilityNodeInfo,
        displayId: Int,
        windowId: Int,
    ) {
        requireSession(request)
        ensureUnlocked()
        if (!node.refresh() || !node.isVisibleToUser) hostFailure(ERR_STALE_NODE)
        if (node.windowId != windowId || node.window?.displayId != displayId) hostFailure(ERR_STALE_NODE)
    }

    private fun findEditableMatches(
        node: AccessibilityNodeInfo,
        target: NodeSnapshot,
        windowId: Int,
        depth: Int,
        visited: IntArray,
        matches: MutableList<AccessibilityNodeInfo>,
    ) {
        if (depth > MAX_TREE_DEPTH || visited[0]++ >= MAX_SNAPSHOT_NODES || matches.size > 1) return
        node.refresh()
        if (node.isEditable && node.isVisibleToUser && matchesTarget(node, target, windowId)) matches += node
        for (index in 0 until minOf(node.childCount, MAX_NODE_CHILDREN)) {
            val child = node.getChild(index) ?: continue
            findEditableMatches(child, target, windowId, depth + 1, visited, matches)
        }
    }

    private fun matchesTarget(
        node: AccessibilityNodeInfo,
        target: NodeSnapshot,
        windowId: Int,
    ): Boolean {
        val current = NodeSnapshotReader.read(node, 0, null, 0)
        return node.windowId == windowId &&
            current.className == target.className &&
            current.viewId == target.viewId &&
            current.packageName == target.packageName &&
            current.bounds == target.bounds &&
            current.inputType == target.inputType &&
            current.text == target.text &&
            current.contentDesc == target.contentDesc &&
            current.hint == target.hint &&
            (current.flags and NodeFlags.PASSWORD) == (target.flags and NodeFlags.PASSWORD)
    }

    private fun foreground(request: JSONObject): JSONObject {
        val packageName = foregroundPackage(requireSession(request).display.displayId)
        return JSONObject().put("packageName", packageName.orEmpty())
    }

    private fun foregroundPackage(displayId: Int): String? =
        allRootTasks().firstOrNull { it.displayId == displayId && it.visible && it.topActivity != null }?.topPackage

    private fun allRootTasks(): List<BackgroundDisplayPlacement.Task> {
        try {
            val service =
                Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                    ?: hostFailure(ERR_FOREGROUND_UNAVAILABLE)
            val tasks =
                Class
                    .forName("android.app.IActivityTaskManager")
                    .getMethod("getAllRootTaskInfos")
                    .invoke(service) as? List<*>
                    ?: hostFailure(ERR_FOREGROUND_UNAVAILABLE)
            return tasks.map { entry ->
                val task = requireNotNull(entry)
                val type = task.javaClass
                val top = type.getField("topActivity").get(task) as? ComponentName
                val base = type.getField("baseActivity").get(task) as? ComponentName
                val childTasks = (type.getField("childTaskNames").get(task) as? Array<*>).orEmpty()
                BackgroundDisplayPlacement.Task(
                    displayId = type.getField("displayId").getInt(task),
                    id = type.getField("taskId").getInt(task),
                    topActivity = top?.flattenToString(),
                    topPackage = top?.packageName,
                    packages =
                        setOfNotNull(top?.packageName, base?.packageName) +
                            childTasks.mapNotNull { child ->
                                (child as? String)?.let { ComponentName.unflattenFromString(it)?.packageName ?: it }
                            },
                    visible = type.getField("visible").getBoolean(task),
                )
            }
        } catch (error: Throwable) {
            when (error) {
                is HostFailure -> raise(error)
                else -> hostFailure(ERR_FOREGROUND_UNAVAILABLE, error)
            }
        }
    }

    private fun release(request: JSONObject): JSONObject {
        releaseSession(sessionId(request))
        return JSONObject()
    }

    private fun releaseSession(id: String) {
        sessions.remove(id)?.let(::releaseSession)
    }

    private fun releaseSession(session: DisplaySession) {
        synchronized(session) {
            try {
                session.virtualDisplay.release()
            } finally {
                session.frames.close()
            }
        }
    }

    private fun requireSession(request: JSONObject): DisplaySession =
        sessions[sessionId(request)]?.takeIf { !closing && it.display.isValid && it.display.displayId > 0 }
            ?: hostFailure(ERR_DISPLAY_UNAVAILABLE)

    private fun validatePoint(
        session: DisplaySession,
        x: Int,
        y: Int,
    ) {
        require(x in 0 until session.width && y in 0 until session.height) { "coordinates" }
    }

    // UID 2000 Host uses the platform's per-display setter; failure must abort instead of using the real display.
    @SuppressLint("BlockedPrivateApi")
    private fun setDisplayId(
        event: InputEvent,
        displayId: Int,
    ) {
        if (displayId <= Display.DEFAULT_DISPLAY) hostFailure(ERR_INJECTION_FAILED)
        runCatching {
            InputEvent::class.java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
            check(
                InputEvent::class.java.getMethod("getDisplayId").invoke(event) == displayId,
            ) { "input display mismatch" }
        }.onFailure { hostFailure(ERR_INJECTION_FAILED, it) }
    }

    private fun inputManager(): InputManager =
        context.getSystemService(InputManager::class.java) ?: hostFailure(ERR_INJECTION_FAILED)

    private fun inject(
        manager: InputManager,
        event: InputEvent,
    ): Boolean =
        runCatching {
            InputManager::class.java
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, event, 2) as Boolean
        }.getOrElse { hostFailure(ERR_INJECTION_FAILED, it) }

    private fun windowType(type: Int): WindowType =
        when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_DIVIDER
            else -> WindowType.UNKNOWN
        }

    private fun snapshotJson(snapshot: ScreenSnapshot): JSONObject =
        JSONObject(AutopJson.default.encodeToString(ScreenSnapshot.serializer(), snapshot))

    private fun nodeJson(snapshot: NodeSnapshot): JSONObject =
        JSONObject(AutopJson.default.encodeToString(NodeSnapshot.serializer(), snapshot))

    private class CaptureState {
        val nodes = mutableListOf<NodeSnapshot>()
        var truncated = false
    }
}

private class AutomationSession(
    private val displayId: Int,
) {
    private val token = Binder()
    private val thread = HandlerThread("autop-bg-automation").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var accessibilityManager: Any? = null

    @Volatile
    private var client: Any? = null

    @Volatile
    private var registrationFailure: Throwable? = null

    @Volatile
    var automation: UiAutomation? = null
        private set

    fun open() {
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connectionHandler =
            InvocationHandler { proxy, method, arguments ->
                when (method.name) {
                    "asBinder" -> token
                    "connect" -> {
                        if (arguments?.isNotEmpty() == true) {
                            client = arguments[0]
                            runCatching { register(requireNotNull(client)) }.onFailure { registrationFailure = it }
                        }
                        null
                    }
                    "disconnect" -> {
                        unregister()
                        null
                    }
                    "toString" -> "AutoPBackgroundDisplay"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        val connection = Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType), connectionHandler)
        val constructor: Constructor<*> =
            UiAutomation::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Looper::class.java,
                connectionType,
            )
        constructor.isAccessible = true
        try {
            runOnThread {
                val instance = constructor.newInstance(displayId, handler.looper, connection) as UiAutomation
                automation = instance
                UiAutomation::class.java
                    .getMethod("connectWithTimeout", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                    .invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, AUTOMATION_TIMEOUT_MS)
                registrationFailure?.let { hostFailure(ERR_AUTOMATION_UNAVAILABLE, it) }
            }
        } catch (error: Throwable) {
            runCatching { close() }
            raise(error)
        }
    }

    fun <T> withAutomation(block: () -> T): T {
        val instance = automation ?: hostFailure(ERR_AUTOMATION_UNAVAILABLE)
        return runOnThread {
            if (automation !== instance) hostFailure(ERR_AUTOMATION_UNAVAILABLE)
            block()
        }
    }

    private fun <T> runOnThread(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val future = CompletableFuture<T>()
        if (!handler.post {
                try {
                    future.complete(block())
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
        ) {
            hostFailure(ERR_AUTOMATION_UNAVAILABLE)
        }
        return try {
            future.get(AUTOMATION_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: java.util.concurrent.ExecutionException) {
            raise(error.cause ?: error)
        } catch (error: Throwable) {
            hostFailure(ERR_AUTOMATION_UNAVAILABLE, error)
        }
    }

    private fun register(value: Any) {
        val managerType = Class.forName("android.view.accessibility.IAccessibilityManager")
        val service =
            checkNotNull(
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "accessibility") as? IBinder,
            ) { "accessibility service" }
        val manager =
            Class
                .forName("android.view.accessibility.IAccessibilityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, service)
        accessibilityManager = manager
        val info =
            AccessibilityServiceInfo().apply {
                packageNames = null
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        AccessibilityServiceInfo::class.java
            .getMethod("setCapabilities", Int::class.javaPrimitiveType)
            .invoke(info, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        val userId =
            Class
                .forName("android.os.UserHandle")
                .getMethod("getUserId", Int::class.javaPrimitiveType)
                .invoke(null, Process.myUid()) as Int
        managerType
            .getMethod(
                "registerUiTestAutomationService",
                IBinder::class.java,
                Class.forName("android.accessibilityservice.IAccessibilityServiceClient"),
                AccessibilityServiceInfo::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(manager, token, value, info, userId, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    private fun unregister() {
        val value = client ?: return
        val manager = accessibilityManager ?: return
        client = null
        runCatching {
            Class
                .forName("android.view.accessibility.IAccessibilityManager")
                .getMethod(
                    "unregisterUiTestAutomationService",
                    Class.forName("android.accessibilityservice.IAccessibilityServiceClient"),
                ).invoke(manager, value)
        }
    }

    fun close() {
        runCatching {
            runOnThread {
                automation?.let { instance ->
                    UiAutomation::class.java.getMethod("disconnect").invoke(instance)
                }
                automation = null
                unregister()
            }
        }
        thread.quitSafely()
    }
}

private fun hostFailure(
    code: String,
    cause: Throwable? = null,
): Nothing = throw HostFailure(code, cause)

private fun raise(error: Throwable): Nothing = throw error

private fun Throwable.isExpectedProtocolFailure(): Boolean =
    when (this) {
        is HostFailure, is IllegalArgumentException, is JSONException, is SecurityException -> true
        else -> false
    }
