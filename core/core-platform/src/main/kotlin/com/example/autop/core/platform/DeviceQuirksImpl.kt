package com.example.autop.core.platform

import android.content.Context
import android.os.Build
import com.example.autop.core.common.DeviceQuirks

/**
 * [DeviceQuirks] 的 Android 实现（docs/tech/02 §4.3）：加载 `assets/rules/device_quirks.json`，
 * 按厂商（比较时小写化）与 minSdk 匹配；所有命中规则的 values 按文件顺序合并，后条覆盖前条。
 * 厂商判断只进规则文件，核心代码禁止按 ROM 分支（任务书硬性规则 2）。
 *
 * [sdkInt] 由 [PlatformCapabilitiesImpl] 注入，`Build.VERSION.SDK_INT` 全工程只允许出现在
 * 那一处与 device-impl；[manufacturer] 默认取 [Build.MANUFACTURER]，测试注入假值。
 */
public class DeviceQuirksImpl(
    private val rules: DeviceQuirksRules,
    private val manufacturer: String,
    private val sdkInt: Int,
) : DeviceQuirks {
    private val merged: Map<String, String> = mergeMatching()

    override fun value(key: String): String? = merged[key]

    private fun mergeMatching(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (quirk in rules.quirks) {
            if (matches(quirk.match)) result.putAll(quirk.values)
        }
        return result
    }

    private fun matches(match: DeviceQuirkMatch): Boolean {
        val maker = manufacturer.lowercase()
        val byMaker = match.manufacturer.isEmpty() || match.manufacturer.any { it.lowercase() == maker }
        return byMaker && sdkInt >= match.minSdk
    }

    public companion object {
        private const val RULES_PATH = "rules/device_quirks.json"

        /** 从 `assets/rules/device_quirks.json` 加载真实规则；[sdkInt] 来自 PlatformCapabilitiesImpl。 */
        public fun fromAssets(
            context: Context,
            sdkInt: Int,
        ): DeviceQuirksImpl {
            val text =
                context.assets
                    .open(RULES_PATH)
                    .bufferedReader()
                    .use { it.readText() }
            return DeviceQuirksImpl(parseDeviceQuirksRules(text), Build.MANUFACTURER, sdkInt)
        }
    }
}
