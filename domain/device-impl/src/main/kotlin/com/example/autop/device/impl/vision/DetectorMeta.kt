/**
 * ONNX 检测器 metadata 解析（vp8 §8.1/§8.2，ADR-0008 决策 2）：类名表以 metadata `names`
 * 为准（按 id 顺序的 JSON 数组），部署点 conf 读 `deploy_conf`（P9 冻结值，不在 App 内调参）。
 * 纯 Kotlin 可单测（宽松解析，不引 JSON 依赖）。
 */
package com.example.autop.device.impl.vision

/** v2 多类检测器 metadata（names 类名表＋部署阈值）。 */
internal data class DetectorMeta(
    /** id → 类名（按 id 顺序）。 */
    val classNames: List<String>,
    /** 部署点置信度阈值（metadata `deploy_conf`，P9 冻结值）。 */
    val deployConf: Double,
) {
    companion object {
        /** 唯一认识的 labels_version（vp8 §8.2）。 */
        const val VERSION_2: String = "2"

        /** metadata 缺失时的部署阈值（P9 冻结值）。 */
        const val DEFAULT_DEPLOY_CONF: Double = 0.26
    }
}

/** 检测器档位裁决（labels_version 判定，ADR-0008 决策 2/3）。 */
internal sealed interface DetectorTier {
    /** v2 多类直出：按 [meta] 映射类别，不再细化。 */
    data class Multi(
        val meta: DetectorMeta,
    ) : DetectorTier

    /** 未声明 labels_version：GPA 单类兜底档（全部按 icon 出框，SubtypeRefiner 细化）。 */
    data object Single : DetectorTier

    /** labels_version 不认识 / v2 缺 names：拒绝加载，不做 v1 猜测。 */
    data object Reject : DetectorTier
}

/** metadata → 档位裁决。 */
internal fun detectorTierOf(meta: Map<String, String>): DetectorTier {
    val version = meta["labels_version"]
    val names = parseClassNames(meta["names"])
    val deploy = meta["deploy_conf"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
    return when {
        version == null -> DetectorTier.Single
        version == DetectorMeta.VERSION_2 && names != null ->
            DetectorTier.Multi(DetectorMeta(names, deploy ?: DetectorMeta.DEFAULT_DEPLOY_CONF))
        else -> DetectorTier.Reject
    }
}

/** `["text", "icon", …]` → 类名列表；形态不对/为空返回 null（宽松解析）。 */
private fun parseClassNames(raw: String?): List<String>? {
    if (raw == null) return null
    val names = CLASS_NAME_REGEX.findAll(raw).map { it.groupValues[1] }.toList()
    return names.ifEmpty { null }
}

/** metadata `names` 的 JSON 字符串数组元素提取。 */
private val CLASS_NAME_REGEX = Regex("\"([^\"]*)\"")
