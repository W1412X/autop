/**
 * 视觉链路调试数据（N208）：检测框＋OCR 归属文本＋状态三态，供调试渲染器生成
 * 「检测渲染图＋逐框识别输入输出裁剪图」（工具测试面板查看）。纯 Kotlin，可 JVM 单测。
 */
package com.example.autop.device.impl.vision

/** 置信度百分比换算系数（detekt MagicNumber）。 */
private const val PERCENT: Int = 100

/** 逐框裁剪图上限（更靠后的框只进检测渲染图，防调试图爆量）。 */
internal const val MAX_CROP_IMAGES: Int = 40

/** 汇总逐框调试数据（阅读序排序并编号，OCR 文本按中心落入的最小框归属）。 */
internal fun boxesOf(
    detections: List<VisualDetection>,
    lines: List<VisualOcrLine>,
): List<VisualDebugBox> {
    val ordered = detections.sortedWith(compareBy({ it.bounds.t }, { it.bounds.l }))
    val texts = arrayOfNulls<String>(ordered.size)
    for (line in lines) {
        val winner =
            ordered.indices
                .filter { line.bounds.centerIn(ordered[it].bounds) }
                .minByOrNull { ordered[it].bounds.area }
        if (winner != null) {
            val cur = texts[winner]
            texts[winner] = if (cur == null) line.text else "$cur ${line.text}"
        }
    }
    return ordered.mapIndexed { i, det ->
        VisualDebugBox(i + 1, det.category, det.confidence, det.bounds, texts[i], det.checked)
    }
}

/** 检测渲染图说明（N208）：全链路第 1 步（检测＋OCR 全图输出）的总览。 */
internal fun detectionCaptionOf(
    boxCount: Int,
    lineCount: Int,
): String = "检测渲染图（第 1 步）：$boxCount 个检测框（红＝类别/置信度）＋$lineCount 条 OCR 行（绿＝识别文本）"

/** 逐框识别 I/O 说明（N208）：裁剪图＝识别输入，OCR 归属文本/状态＝识别输出。 */
internal fun captionOf(box: VisualDebugBox): String {
    val text = box.text?.let { "「$it」" } ?: "无"
    val conf = (box.confidence * PERCENT).toInt()
    return "#${box.index} ${box.category} $conf%｜输入=框内裁剪｜OCR 归属=$text｜状态=${stateOf(box.checked)}"
}

/** 状态三态文案（N208）。 */
internal fun stateOf(checked: Boolean?): String =
    when (checked) {
        true -> "on"
        false -> "off"
        null -> "未知"
    }
