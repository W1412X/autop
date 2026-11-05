// SPAKE2 配对上下文的 JNI 封装（N199）。
// 搬运自 Shizuku (Apache-2.0, RikkaApps/Shizuku) AdbPairingClient.kt 内的 private class PairingContext，
// 拆为独立文件（JNI RegisterNatives 按 com/example/autop/device/impl/adb/PairingContext 注册），
// native 实现见 src/main/jni/adb_pairing.cpp。逻辑原样。
package com.example.autop.device.impl.adb

internal class PairingContext private constructor(
    private val nativePtr: Long,
) {
    val msg: ByteArray = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(input: ByteArray) = nativeEncrypt(nativePtr, input)

    fun decrypt(input: ByteArray) = nativeDecrypt(nativePtr, input)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(
        nativePtr: Long,
        theirMsg: ByteArray,
    ): Boolean

    private external fun nativeEncrypt(
        nativePtr: Long,
        inbuf: ByteArray,
    ): ByteArray?

    private external fun nativeDecrypt(
        nativePtr: Long,
        inbuf: ByteArray,
    ): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            // N201：搬运拆类时曾遗漏——必须在任何 native 调用前加载 so，
            // 触发 JNI_OnLoad → RegisterNatives（adb_pairing.cpp 按本类名注册）。
            System.loadLibrary("adb")
        }

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(
            isClient: Boolean,
            password: ByteArray,
        ): Long
    }
}
