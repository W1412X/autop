/**
 * 视觉模型资产装载（docs/design/visual-screen-parsing §9）：
 * assets/vision/ 下的 ONNX 权重＋字典，构建期 SHA-256 清单（vision/manifest.json）校验；
 * NNAPI 加速尝试失败回退 CPU（§12 机型碎片化对策）。
 */
package com.example.autop.device.impl.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.res.AssetManager
import java.security.MessageDigest

/**
 * 视觉模型资产仓库（会话惰性创建，进程内缓存）。装载函数注入以便纯 JVM 测试校验逻辑。
 * [ortThreads]：按模型名给出 ORT intra-op 线程数（≤0 用 ORT 默认），在会话创建时读取一次（ADR-0026）。
 * [openAsset] 放最后，便于 `VisionModelStore { name -> … }` 写法。
 */
internal class VisionModelStore(
    private val ortThreads: (String) -> Int = { 0 },
    private val openAsset: (String) -> ByteArray?,
) {
    /** AssetManager 装载（assets/vision/ 前缀）。委托参数中的 runCatching 会隐式捕获构造中的 this，故抽辅助函数。 */
    constructor(assets: AssetManager, ortThreads: (String) -> Int = { 0 }) :
        this(ortThreads, { name -> readAsset(assets, name) })

    private val sessions = mutableMapOf<String, OrtSession?>()

    /** 读取 assets/vision/[name] 原始字节；缺失/校验失败返回 null。 */
    fun bytes(name: String): ByteArray? = openAsset(name)?.takeIf { verified(name, it) }

    /** 读取文本资产（字符集等）。 */
    fun text(name: String): String? = bytes(name)?.let { String(it, Charsets.UTF_8) }

    /** 惰性创建 ONNX 会话；资产缺失/校验失败/加载失败返回 null。 */
    @Synchronized
    fun session(name: String): OrtSession? {
        if (sessions.containsKey(name)) return sessions[name]
        val created = bytes(name)?.let { createSession(it, ortThreads(name)) }
        sessions[name] = created
        return created
    }

    /** SHA-256 清单校验：无清单/未列条目视为开发模式放行，已列条目必须匹配。 */
    private fun verified(
        name: String,
        data: ByteArray,
    ): Boolean {
        val expected = readManifest()?.get(name) ?: return true
        return sha256(data).equals(expected, ignoreCase = true)
    }

    private fun readManifest(): Map<String, String>? {
        val raw = bytesRaw(MANIFEST) ?: return null
        return runCatching {
            val text = String(raw, Charsets.UTF_8)
            MANIFEST_REGEX.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        }.getOrNull()
    }

    private fun bytesRaw(name: String): ByteArray? = openAsset(name)

    /** NNAPI 会话，失败回退 CPU 会话（§12）。 */
    private fun createSession(
        data: ByteArray,
        threads: Int,
    ): OrtSession? {
        val env = OrtEnvironment.getEnvironment()
        return runCatching { env.createSession(data, options(nnapi = true, threads = threads)) }
            .recoverCatching { env.createSession(data, options(nnapi = false, threads = threads)) }
            .getOrNull()
    }

    private fun options(
        nnapi: Boolean,
        threads: Int,
    ): SessionOptions =
        SessionOptions().apply {
            setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            if (threads > 0) setIntraOpNumThreads(threads)
            if (nnapi) addNnapi()
        }

    private fun sha256(data: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST = "manifest.json"

        /** "name": "sha256" 提取（宽松解析，避免额外 JSON 依赖）。 */
        val MANIFEST_REGEX = Regex("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
    }
}

/** assets/vision/ 资产目录前缀。 */
private const val ASSET_DIR = "vision"

/** 从 [assets] 读取 assets/vision/[name] 字节（缺失返回 null）。 */
private fun readAsset(
    assets: AssetManager,
    name: String,
): ByteArray? = runCatching { assets.open("$ASSET_DIR/$name").use { it.readBytes() } }.getOrNull()
