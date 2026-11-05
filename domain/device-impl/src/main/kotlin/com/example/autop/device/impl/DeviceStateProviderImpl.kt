/**
 * 设备状态（docs/tech/05 §1.3）：电量、网络、锁屏与前台应用。
 */
package com.example.autop.device.impl

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.example.autop.device.DeviceState
import com.example.autop.device.DeviceStateProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** [DeviceStateProvider] 实现。 */
@Singleton
public class DeviceStateProviderImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val foreground: ForegroundTracker,
    ) : DeviceStateProvider {
        override fun current(): DeviceState {
            val battery = context.getSystemService(BatteryManager::class.java)
            val fg = foreground.current.value
            return DeviceState(
                batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = battery?.isCharging == true,
                networkType = networkType(),
                screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive == true,
                keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
                foregroundPackage = fg?.packageName,
            )
        }

        /** 电池/亮屏广播与前台切换驱动的变化流。 */
        @OptIn(ExperimentalCoroutinesApi::class)
        override val changes: Flow<DeviceState> =
            callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            trySend(current())
                        }
                    }
                val filter =
                    IntentFilter().apply {
                        addAction(Intent.ACTION_BATTERY_CHANGED)
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    }
                context.registerReceiver(receiver, filter)
                val job = launch { foreground.current.collect { trySend(current()) } }
                awaitClose {
                    context.unregisterReceiver(receiver)
                    job.cancel()
                }
            }

        private fun networkType(): String {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            return when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        }
    }
