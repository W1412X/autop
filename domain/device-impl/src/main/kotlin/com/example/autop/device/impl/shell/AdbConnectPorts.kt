package com.example.autop.device.impl.shell

import android.content.Context
import androidx.core.content.edit
import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.impl.adb.AdbMdns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 注入通道与 shell 宿主共用的 ADB 配置存储名。 */
internal const val ADB_PREFS_NAME: String = "autop_adb"

private const val KEY_CONNECT_PORT = "connect_port"

/**
 * 无线调试连接端口：先用上次成功的端口，失败后由调用方 [forget]，再经 mDNS（`_adb-tls-connect._tcp`）发现。
 * 注入通道（[AdbShellInput]）与 shell 宿主（[ShellPool]）共用，保证两者连接的是同一个 adbd。
 */
@Singleton
public class AdbConnectPorts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val configProvider: ConfigProvider,
    ) {
        private val prefs
            get() = context.getSharedPreferences(ADB_PREFS_NAME, Context.MODE_PRIVATE)

        /** 上次成功连接的端口；没有则 null。 */
        fun cached(): Int? = prefs.getInt(KEY_CONNECT_PORT, -1).takeIf { it > 0 }

        fun remember(port: Int) {
            prefs.edit { putInt(KEY_CONNECT_PORT, port) }
        }

        fun forget() {
            prefs.edit { remove(KEY_CONNECT_PORT) }
        }

        /** 经 mDNS 发现连接端口，超过配置的 `device.shell.discoverTimeoutMs` 返回 null。 */
        suspend fun discover(): Int? {
            val timeoutMs = configProvider.current()[DeviceConfig.SHELL].discoverTimeoutMs
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val mdns =
                        AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                            if (continuation.isActive) {
                                continuation.resume(if (port > 0) port else null)
                            }
                        }
                    mdns.start()
                    continuation.invokeOnCancellation { mdns.stop() }
                }
            }
        }
    }
