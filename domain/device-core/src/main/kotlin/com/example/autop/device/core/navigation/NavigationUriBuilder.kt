/**
 * 按规则表生成导航 URI（intent-tool §3.1、�?.2）。纯函数，单元测试覆盖每个应用与每种出行方式�? */
package com.example.autop.device.core.navigation

import com.example.autop.device.core.IntentSpec
import java.math.BigDecimal
import java.math.RoundingMode

/** URI 构造�?*/
public object NavigationUriBuilder {
    /**
     * �?[rule] 生成导航 URI；该应用不支�?[IntentSpec.Navigate.mode] 时返�?null�?     * [source] 是本应用包名，写�?`{source}`�?     */
    public fun build(
        rule: NavigationAppRule,
        nav: IntentSpec.Navigate,
        source: String,
    ): String? {
        val modeValue = rule.uri.modes[nav.mode.id] ?: return null
        val vars = placeholderValues(nav, modeValue, source)
        val pairs =
            rule.uri.params.mapNotNull { param ->
                if (!applies(param, nav)) return@mapNotNull null
                val value = expand(param.value, vars) ?: return@mapNotNull null
                "${percentEncode(param.key)}=${percentEncode(value)}"
            }
        return if (pairs.isEmpty()) rule.uri.base else "${rule.uri.base}?${pairs.joinToString("&")}"
    }

    /** `geo:` 兜底链接（系统选择处理者，只保证显示位置）�?*/
    public fun geo(nav: IntentSpec.Navigate): String {
        val lat = formatCoord(nav.lat)
        val lng = formatCoord(nav.lng)
        if (lat != null && lng != null) {
            val label = nav.destination?.let { "(${percentEncode(it)})" }.orEmpty()
            return "geo:$lat,$lng?q=$lat,$lng$label"
        }
        val name = nav.destination ?: return GEO_UNKNOWN_PLACE
        return "geo:0,0?q=${percentEncode(name)}"
    }

    private fun placeholderValues(
        nav: IntentSpec.Navigate,
        modeValue: String,
        source: String,
    ): Map<String, String?> =
        mapOf(
            "name" to nav.destination,
            "lat" to formatCoord(nav.lat),
            "lng" to formatCoord(nav.lng),
            "mode" to modeValue,
            "source" to source,
        )

    /** 参数是否输出：`when` 条件�?`onlyModes` 都要满足�?*/
    private fun applies(
        param: NavigationParamRule,
        nav: IntentSpec.Navigate,
    ): Boolean {
        val onlyModes = param.onlyModes
        if (onlyModes != null && nav.mode.id !in onlyModes) return false
        val hasName = nav.destination != null
        val hasCoord = nav.lat != null && nav.lng != null
        return when (param.`when`) {
            WHEN_ALWAYS -> true
            WHEN_HAS_NAME -> hasName
            WHEN_HAS_COORD -> hasCoord
            WHEN_NAME_ONLY -> hasName && !hasCoord
            WHEN_COORD_ONLY -> hasCoord && !hasName
            WHEN_COORD_AND_NAME -> hasCoord && hasName
            else -> false
        }
    }

    /** 展开模板；引用的占位符没有值时返回 null（该参数跳过）�?*/
    private fun expand(
        template: String,
        vars: Map<String, String?>,
    ): String? {
        val names = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()
        if (names.any { vars[it] == null }) return null
        return PLACEHOLDER.replace(template) { match -> vars[match.groupValues[1]].orEmpty() }
    }

    /** 坐标：至�?6 位小数，去掉末尾 0 与多余小数点，不出现科学计数法�?*/
    internal fun formatCoord(value: Double?): String? =
        value?.let {
            BigDecimal(it.toString())
                .setScale(COORD_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }

    /**
     * UTF-8 百分号编码：字母数字�?`-._~` 不编码，空格编为 `%20`（不�?`+`），其余逐字节编码�?     */
    internal fun percentEncode(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and BYTE_MASK
            if (isUnreserved(code)) {
                out.append(code.toChar())
            } else {
                out.append('%')
                out.append(HEX_DIGITS[code shr NIBBLE_BITS])
                out.append(HEX_DIGITS[code and NIBBLE_MASK])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(code: Int): Boolean =
        code in 'A'.code..'Z'.code ||
            code in 'a'.code..'z'.code ||
            code in '0'.code..'9'.code ||
            code == '-'.code ||
            code == '.'.code ||
            code == '_'.code ||
            code == '~'.code

    private const val COORD_SCALE: Int = 6
    private const val BYTE_MASK: Int = 0xFF
    private const val NIBBLE_BITS: Int = 4
    private const val NIBBLE_MASK: Int = 0x0F
    private const val HEX_DIGITS: String = "0123456789ABCDEF"
    private const val GEO_UNKNOWN_PLACE: String = "geo:0,0"
    private val PLACEHOLDER = Regex("\\{([^{}]*)\\}")
}
