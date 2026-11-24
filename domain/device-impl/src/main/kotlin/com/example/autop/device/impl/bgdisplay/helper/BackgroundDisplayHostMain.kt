@file:Suppress("TooGenericExceptionCaught")

package com.example.autop.device.impl.bgdisplay.helper

import android.accessibilityservice.AccessibilityServiceInfo
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
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_AUTOMATION_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_BAD_REQUEST
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_CAPTURE_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_DISPLAY_CREATE_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_DISPLAY_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_FOREGROUND_UNAVAILABLE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_INJECTION_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_LAUNCH_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_NO_FOREGROUND
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_SET_TEXT_FAILED
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_STALE_NODE
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.ERR_TIMEOUT
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
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_REQUEST_CHARS
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.MAX_TEXT_BYTES
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
import com.example.autop.device.impl.bgdisplay.helper.BackgroundDisplayWire.keyCodes
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
private const val CAPTURE_RETRY_COUNT = 40
private const val CAPTURE_RETRY_MS = 50L
private const val LAUNCH_POLL_MS = 50L
private const val AUTOMATION_TIMEOUT_MS = 3000L
private const val AUTOMATION_OPERATION_TIMEOUT_MS = 30_000L
private const val HOST_CLOSE_TIMEOUT_SECONDS = 10L
private const val SWIPE_STEP_PX = 48.0
private const val SWIPE_MAX_STEPS = 40

private data class DisplaySession(
    val id: String,
    val display: Display,
    val virtualDisplay: VirtualDisplay,
    val reader: ImageReader,
    val width: Int,
    val height: Int,
    val jpegQuality: Int,
)

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
        runCatching { executor.awaitTermination(HOST_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach(::releaseSession)
        runCatching { automationSession?.close() }
        automationSession = null
    }

    private val requestHandlers =
        mapOf<String, (JSONObject) -> JSONObject>(
            OP_CREATE to ::create,
            OP_LAUNCH to ::launch,
            OP_TAP to ::tap,
            OP_SWIPE to ::swipe,
            OP_KEY to ::key,
            OP_KEY_COMBO to ::keyCombo,
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
    ): JSONObject {
        requestHandlers[op]?.let { return it(request) }
        readHandlers[op]?.let { return it(request) }
        return when (op) {
            OP_RELEASE -> release(request)
            OP_SHUTDOWN -> {
                shutdown = true
                JSONObject().put("exit", true)
            }
            else -> hostFailure(ERR_UNKNOWN_OP)
        }
    }

    private fun create(request: JSONObject): JSONObject {
        if (closing) hostFailure(ERR_DISPLAY_CREATE_FAILED)
        val id = sessionId(request)
        val newWidth = int(request, "width", min = 1, max = MAX_DISPLAY_DIMENSION)
        val newHeight = int(request, "height", min = 1, max = MAX_DISPLAY_DIMENSION)
        val newDpi = int(request, "dpi", min = 72, max = 640)
        val jpegQuality = int(request, "jpegQuality", min = 1, max = 100)
        require(newWidth.toLong() * newHeight <= MAX_DISPLAY_PIXELS) { "display size" }
        releaseSession(id)
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        try {
            val createdReader = ImageReader.newInstance(newWidth, newHeight, android.graphics.PixelFormat.RGBA_8888, 2)
            reader = createdReader
            val displays = checkNotNull(context.getSystemService(DisplayManager::class.java)) { "display manager" }
            val createdDisplay =
                checkNotNull(
                    displays.createVirtualDisplay(
                        "autop-bg-${Process.myPid()}-$id",
                        newWidth,
                        newHeight,
                        newDpi,
                        createdReader.surface,
                        virtualDisplayFlags(),
                        null,
                        null,
                    ),
                ) { "virtual display" }
            display = createdDisplay
            val target = checkNotNull(createdDisplay.display) { "display id" }
            setImePolicyHide(target.displayId)
            automation()
            sessions[id] =
                DisplaySession(
                    id = id,
                    display = target,
                    virtualDisplay = createdDisplay,
                    reader = createdReader,
                    width = newWidth,
                    height = newHeight,
                    jpegQuality = jpegQuality,
                )
            return JSONObject()
                .put("displayId", target.displayId)
                .put("width", newWidth)
                .put("height", newHeight)
                .put("dpi", newDpi)
        } catch (error: Throwable) {
            Log.e("BackgroundDisplayHost", "display creation failed", error)
            runCatching { display?.release() }
            runCatching { reader?.close() }
            when (error) {
                is HostFailure -> raise(error)
                else -> hostFailure(ERR_DISPLAY_CREATE_FAILED, error)
            }
        }
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(display.displayId) }
        val pending =
            PendingIntent.getActivity(
                displayContext,
                display.displayId,
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                options.toBundle(),
            )
        try {
            pending.send(displayContext, 0, null, null, null, null, options.toBundle())
        } catch (error: Throwable) {
            hostFailure(ERR_LAUNCH_FAILED, error)
        } finally {
            runCatching { pending.cancel() }
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (foregroundPackage(session.display.displayId) == packageName) return JSONObject()
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) hostFailure(ERR_TIMEOUT)
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
                    val windows = (target as? List<*>)?.filterIsInstance<AccessibilityWindowInfo>().orEmpty()
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
        val reader = session.reader
        var image: Image? = null
        try {
            repeat(CAPTURE_RETRY_COUNT) {
                if (image == null) image = reader.acquireLatestImage()
                if (image == null) SystemClock.sleep(CAPTURE_RETRY_MS)
            }
            val captured = image ?: hostFailure(ERR_CAPTURE_FAILED)
            val bytes = encodeJpeg(captured, session.jpegQuality)
            return JSONObject()
                .put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("width", captured.width)
                .put("height", captured.height)
        } catch (error: Throwable) {
            when (error) {
                is HostFailure -> raise(error)
                else -> hostFailure(ERR_CAPTURE_FAILED, error)
            }
        } finally {
            runCatching { image?.close() }
        }
    }

    private fun encodeJpeg(
        image: Image,
        quality: Int,
    ): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            buffer.position(y * plane.rowStride)
            buffer.asIntBuffer().get(pixels, y * image.width, image.width)
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "jpeg encode" }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun tap(request: JSONObject): JSONObject {
        val x = int(request, "x")
        val y = int(request, "y")
        val duration = long(request, "durationMs", min = 0L, max = MAX_GESTURE_MS)
        val session = requireSession(request)
        validatePoint(session, x, y)
        val displayId = session.display.displayId
        val manager = inputManager()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + duration, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        var acceptedDown = false
        var acceptedUp = false
        var failure: Throwable? = null
        try {
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            setDisplayId(down, displayId)
            setDisplayId(up, displayId)
            acceptedDown = inject(manager, down)
            if (acceptedDown) SystemClock.sleep(duration)
        } catch (error: Throwable) {
            failure = error
        }
        if (acceptedDown) acceptedUp = runCatching { inject(manager, up) }.isSuccess
        down.recycle()
        up.recycle()
        failure?.let(::raise)
        if (!acceptedDown || !acceptedUp) hostFailure(ERR_INJECTION_FAILED)
        return JSONObject()
    }

    private fun swipe(request: JSONObject): JSONObject {
        val fromX = int(request, "fromX")
        val fromY = int(request, "fromY")
        val toX = int(request, "toX")
        val toY = int(request, "toY")
        val duration = long(request, "durationMs", min = 0L, max = MAX_GESTURE_MS)
        val session = requireSession(request)
        validatePoint(session, fromX, fromY)
        validatePoint(session, toX, toY)
        val displayId = session.display.displayId
        val manager = inputManager()
        val now = SystemClock.uptimeMillis()
        val delta = maxOf(kotlin.math.abs(fromX - toX), kotlin.math.abs(fromY - toY))
        val steps = maxOf(1, minOf(SWIPE_MAX_STEPS, kotlin.math.ceil(delta / SWIPE_STEP_PX).toInt()))
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX.toFloat(), fromY.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + duration, MotionEvent.ACTION_UP, toX.toFloat(), toY.toFloat(), 0)
        var acceptedDown = false
        var acceptedUp = false
        var failure: Throwable? = null
        try {
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            setDisplayId(down, displayId)
            setDisplayId(up, displayId)
            acceptedDown = inject(manager, down)
            if (acceptedDown) {
                injectSwipeMoves(manager, displayId, SwipePath(fromX, fromY, toX, toY, duration, steps, now))
            }
        } catch (error: Throwable) {
            failure = error
        }
        if (acceptedDown) acceptedUp = runCatching { inject(manager, up) }.isSuccess
        down.recycle()
        up.recycle()
        failure?.let(::raise)
        if (!acceptedDown || !acceptedUp) hostFailure(ERR_INJECTION_FAILED)
        return JSONObject()
    }

    private fun injectSwipeMoves(
        manager: InputManager,
        displayId: Int,
        path: SwipePath,
    ) {
        for (step in 1..path.steps) {
            val eventTime = path.now + path.duration * step / path.steps
            SystemClock.sleep((eventTime - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            val x = path.fromX + (path.toX - path.fromX) * step / path.steps
            val y = path.fromY + (path.toY - path.fromY) * step / path.steps
            val move = MotionEvent.obtain(path.now, eventTime, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat(), 0)
            try {
                move.setSource(InputDevice.SOURCE_TOUCHSCREEN)
                setDisplayId(move, displayId)
                if (!inject(manager, move)) hostFailure(ERR_INJECTION_FAILED)
            } finally {
                move.recycle()
            }
        }
    }

    private fun key(request: JSONObject): JSONObject {
        val code = int(request, "code", min = 1, max = MAX_KEY_CODE)
        injectKeys(requireSession(request).display.displayId, listOf(code))
        return JSONObject()
    }

    private fun keyCombo(request: JSONObject): JSONObject {
        injectKeys(requireSession(request).display.displayId, keyCodes(request))
        return JSONObject()
    }

    private fun injectKeys(
        displayId: Int,
        codes: List<Int>,
    ) {
        val manager = inputManager()
        val now = SystemClock.uptimeMillis()
        val pressed = mutableListOf<Int>()
        var metaState = 0
        var failure: Throwable? = null
        try {
            for (code in codes) {
                val event = keyEvent(now, KeyEvent.ACTION_DOWN, code, metaState)
                setDisplayId(event, displayId)
                if (!inject(manager, event)) hostFailure(ERR_INJECTION_FAILED)
                pressed += code
                metaState = metaAfterDown(metaState, code)
            }
        } catch (error: Throwable) {
            failure = error
        }
        var allReleased = true
        for (code in pressed.asReversed()) {
            val event = keyEvent(now, KeyEvent.ACTION_UP, code, metaState)
            try {
                setDisplayId(event, displayId)
                if (!inject(manager, event)) allReleased = false
            } catch (_: Throwable) {
                allReleased = false
            } finally {
                metaState = metaAfterUp(metaState, code)
            }
        }
        failure?.let(::raise)
        if (!allReleased) hostFailure(ERR_INJECTION_FAILED)
    }

    private fun keyEvent(
        downTime: Long,
        action: Int,
        code: Int,
        metaState: Int,
    ): KeyEvent =
        KeyEvent(
            downTime,
            downTime,
            action,
            code,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )

    private fun metaAfterDown(
        meta: Int,
        code: Int,
    ): Int =
        when (code) {
            KeyEvent.KEYCODE_CTRL_LEFT -> meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_CTRL_RIGHT -> meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            KeyEvent.KEYCODE_SHIFT_LEFT -> meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_RIGHT -> meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            KeyEvent.KEYCODE_ALT_RIGHT -> meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            KeyEvent.KEYCODE_META_LEFT -> meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            KeyEvent.KEYCODE_META_RIGHT -> meta or KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
            else -> meta
        }

    private fun metaAfterUp(
        meta: Int,
        code: Int,
    ): Int =
        when (code) {
            KeyEvent.KEYCODE_CTRL_LEFT -> meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON).inv()
            KeyEvent.KEYCODE_CTRL_RIGHT -> meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON).inv()
            KeyEvent.KEYCODE_SHIFT_LEFT -> meta and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON).inv()
            KeyEvent.KEYCODE_SHIFT_RIGHT -> meta and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON).inv()
            KeyEvent.KEYCODE_ALT_LEFT -> meta and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON).inv()
            KeyEvent.KEYCODE_ALT_RIGHT -> meta and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON).inv()
            KeyEvent.KEYCODE_META_LEFT -> meta and (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON).inv()
            KeyEvent.KEYCODE_META_RIGHT -> meta and (KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON).inv()
            else -> meta
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
        if (target.flags and NodeFlags.EDITABLE == 0) hostFailure(ERR_STALE_NODE)
        val windows = targetWindows(displayId)
        if (windows.none { it.id == windowId }) hostFailure(ERR_STALE_NODE)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        val visited = IntArray(1)
        for (window in windows) {
            val root = window.root ?: continue
            findEditableMatches(root, target, windowId, 0, visited, matches)
        }
        val node = matches.singleOrNull() ?: hostFailure(ERR_STALE_NODE)
        val arguments =
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            hostFailure(ERR_SET_TEXT_FAILED)
        }
        return JSONObject()
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
        val packageName = foregroundPackage(requireSession(request).display.displayId) ?: hostFailure(ERR_NO_FOREGROUND)
        return JSONObject().put("packageName", packageName)
    }

    private fun foregroundPackage(displayId: Int): String? {
        try {
            val service =
                Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                    ?: hostFailure(ERR_FOREGROUND_UNAVAILABLE)
            val tasks =
                Class
                    .forName("android.app.IActivityTaskManager")
                    .getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
                    .invoke(service, displayId) as? List<*>
                    ?: hostFailure(ERR_FOREGROUND_UNAVAILABLE)
            for (task in tasks) {
                val component = componentName(task, "topActivity") ?: componentName(task, "resumedActivity")
                if (component != null) return component.packageName
            }
            return null
        } catch (error: Throwable) {
            when (error) {
                is HostFailure -> raise(error)
                else -> hostFailure(ERR_FOREGROUND_UNAVAILABLE, error)
            }
        }
    }

    private fun componentName(
        task: Any?,
        field: String,
    ): ComponentName? {
        val value = runCatching { requireNotNull(task).javaClass.getField(field).get(task) }.getOrNull()
        return value as? ComponentName
    }

    private fun release(request: JSONObject): JSONObject {
        releaseSession(sessionId(request))
        return JSONObject()
    }

    private fun releaseSession(id: String) {
        sessions.remove(id)?.let(::releaseSession)
    }

    private fun releaseSession(session: DisplaySession) {
        runCatching { session.virtualDisplay.release() }
        runCatching { session.reader.close() }
    }

    private fun requireSession(request: JSONObject): DisplaySession =
        sessions[sessionId(request)]?.takeUnless { closing } ?: hostFailure(ERR_DISPLAY_UNAVAILABLE)

    private fun validatePoint(
        session: DisplaySession,
        x: Int,
        y: Int,
    ) {
        require(x in 0 until session.width && y in 0 until session.height) { "coordinates" }
    }

    private fun inputManager(): InputManager =
        context.getSystemService(InputManager::class.java) ?: hostFailure(ERR_INJECTION_FAILED)

    private fun setDisplayId(
        event: InputEvent,
        displayId: Int,
    ) {
        runCatching {
            InputEvent::class.java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }.onFailure { hostFailure(ERR_INJECTION_FAILED, it) }
    }

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

    private data class SwipePath(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val duration: Long,
        val steps: Int,
        val now: Long,
    )
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
