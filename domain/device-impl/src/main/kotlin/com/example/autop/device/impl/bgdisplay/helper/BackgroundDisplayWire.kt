package com.example.autop.device.impl.bgdisplay.helper

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object BackgroundDisplayWire {
    const val PROTOCOL_VERSION: Int = 2
    const val EXPECTED_UID: Int = 2000

    const val OP_CREATE: String = "create"
    const val OP_LAUNCH: String = "launch"
    const val OP_SNAPSHOT: String = "snapshot"
    const val OP_CAPTURE: String = "capture"
    const val OP_TAP: String = "tap"
    const val OP_SWIPE: String = "swipe"
    const val OP_KEY: String = "key"
    const val OP_KEY_COMBO: String = "keyCombo"
    const val OP_FOCUSED_INPUT: String = "focusedInput"
    const val OP_SET_TEXT: String = "setText"
    const val OP_NODE_ACTION: String = "nodeAction"
    const val OP_FOREGROUND: String = "foreground"
    const val OP_RELEASE: String = "release"
    const val OP_SHUTDOWN: String = "shutdown"

    const val ERR_BAD_REQUEST: String = "bad_request"
    const val ERR_UNKNOWN_OP: String = "unknown_op"
    const val ERR_UNSUPPORTED_ANDROID: String = "unsupported_android"
    const val ERR_UID_MISMATCH: String = "uid_mismatch"
    const val ERR_VERSION_MISMATCH: String = "version_mismatch"
    const val ERR_DISPLAY_UNAVAILABLE: String = "display_unavailable"
    const val ERR_DISPLAY_CREATE_FAILED: String = "display_create_failed"
    const val ERR_AUTOMATION_UNAVAILABLE: String = "automation_unavailable"
    const val ERR_TREE_UNAVAILABLE: String = "tree_unavailable"
    const val ERR_CAPTURE_FAILED: String = "capture_failed"
    const val ERR_LAUNCH_FAILED: String = "launch_failed"
    const val ERR_FOREGROUND_UNAVAILABLE: String = "foreground_unavailable"
    const val ERR_INJECTION_FAILED: String = "injection_failed"
    const val ERR_INPUT_NOT_FOUND: String = "input_not_found"
    const val ERR_STALE_NODE: String = "stale_node"
    const val ERR_SET_TEXT_FAILED: String = "set_text_failed"
    const val ERR_PERMISSION_DENIED: String = "permission_denied"
    const val ERR_TIMEOUT: String = "timeout"
    const val ERR_INTERNAL: String = "internal_error"

    const val MAX_REQUEST_CHARS: Int = 262_144
    const val MAX_ID_CHARS: Int = 128
    const val MAX_OP_BYTES: Int = 32
    const val MAX_PACKAGE_BYTES: Int = 256
    const val MAX_TEXT_BYTES: Int = 16_384
    const val MAX_NODE_JSON_BYTES: Int = 131_072
    const val MAX_KEY_COMBO_SIZE: Int = 8
    const val MAX_KEY_CODE: Int = 4096
    const val MAX_GESTURE_MS: Long = 60_000L
    const val MAX_LAUNCH_TIMEOUT_MS: Long = 30_000L
    const val MAX_DISPLAY_DIMENSION: Int = 4096
    const val MAX_DISPLAY_PIXELS: Int = 8_388_608

    fun ready(uid: Int): JSONObject =
        JSONObject()
            .put("ready", true)
            .put("uid", uid)
            .put("version", PROTOCOL_VERSION)

    fun success(id: Any?): JSONObject = withId(id).put("ok", true)

    fun failure(
        id: Any?,
        code: String,
    ): JSONObject = withId(id).put("ok", false).put("error", code)

    fun requestId(request: JSONObject): Any? {
        val id = request.opt("id")
        require(id !== null && id !== JSONObject.NULL) { "id" }
        require(id is String || id is Number) { "id" }
        if (id is String) require(utf8Size(id) <= MAX_ID_CHARS) { "id" }
        return id
    }

    fun string(
        request: JSONObject,
        key: String,
        maxBytes: Int = MAX_REQUEST_CHARS,
    ): String {
        val value = request.opt(key)
        require(value is String && utf8Size(value) <= maxBytes) { key }
        return value
    }

    fun packageName(request: JSONObject): String {
        val value = string(request, "packageName", MAX_PACKAGE_BYTES)
        require(PACKAGE_NAME.matches(value)) { "packageName" }
        return value
    }

    fun sessionId(request: JSONObject): String {
        val value = string(request, "sessionId", MAX_ID_CHARS)
        require(value.isNotBlank()) { "sessionId" }
        return value
    }

    fun int(
        request: JSONObject,
        key: String,
        min: Int = Int.MIN_VALUE,
        max: Int = Int.MAX_VALUE,
    ): Int {
        val value = request.opt(key) as? Number ?: throw IllegalArgumentException(key)
        val parsed = value.toInt()
        require(value.toDouble() == parsed.toDouble() && parsed in min..max) { key }
        return parsed
    }

    fun long(
        request: JSONObject,
        key: String,
        min: Long = Long.MIN_VALUE,
        max: Long = Long.MAX_VALUE,
    ): Long {
        val value = request.opt(key) as? Number ?: throw IllegalArgumentException(key)
        val parsed = value.toLong()
        require(value.toDouble() == parsed.toDouble() && parsed in min..max) { key }
        return parsed
    }

    fun keyCodes(request: JSONObject): List<Int> {
        val value = request.opt("codes") as? JSONArray ?: throw IllegalArgumentException("codes")
        require(value.length() in 1..MAX_KEY_COMBO_SIZE) { "codes" }
        return (0 until value.length()).map { index ->
            val code = value.opt(index) as? Number ?: throw IllegalArgumentException("codes")
            val parsed = code.toInt()
            require(code.toDouble() == parsed.toDouble() && parsed in 1..MAX_KEY_CODE) { "codes" }
            parsed
        }
    }

    fun intArray(
        request: JSONObject,
        key: String,
        maxSize: Int,
        min: Int = Int.MIN_VALUE,
    ): List<Int> {
        val value = request.opt(key) as? JSONArray ?: throw IllegalArgumentException(key)
        require(value.length() <= maxSize) { key }
        return (0 until value.length()).map { index ->
            val item = value.opt(index) as? Number ?: throw IllegalArgumentException(key)
            val parsed = item.toInt()
            require(item.toDouble() == parsed.toDouble() && parsed in min..Int.MAX_VALUE) { key }
            parsed
        }
    }

    fun stableError(error: Throwable): String =
        when (error) {
            is HostFailure -> error.code
            is JSONException, is IllegalArgumentException -> ERR_BAD_REQUEST
            is SecurityException -> ERR_PERMISSION_DENIED
            else -> ERR_INTERNAL
        }

    private fun withId(id: Any?): JSONObject = JSONObject().apply { if (id != null) put("id", id) }

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
}

internal class HostFailure(
    val code: String,
    cause: Throwable? = null,
) : RuntimeException(code, cause)
