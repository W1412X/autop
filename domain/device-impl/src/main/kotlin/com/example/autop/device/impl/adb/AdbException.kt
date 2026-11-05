// ADB 异常（N199）。
// 搬运自 Shizuku (Apache-2.0, RikkaApps/Shizuku) manager/src/main/java/moe/shizuku/manager/adb/AdbException.kt，原样。
package com.example.autop.device.impl.adb

open class AdbException : Exception {
    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(
    cause: Throwable,
) : AdbException(cause)
