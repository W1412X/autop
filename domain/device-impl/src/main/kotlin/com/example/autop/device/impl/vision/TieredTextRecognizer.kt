/**
 * rec 双档级联（docs/design/ocr-accuracy-uplift 阶段 B）：mobile rec 快路径跑全图，
 * 低置信行（CTC 行分 < upgradeConf）升级 server rec 重识，名额受 maxUpgrades 约束控 P95；
 * server 资产缺失/加载失败自动退单档 mobile。整帧批量入口 [recognizeAll]，纯 Kotlin 可单测。
 */
package com.example.autop.device.impl.vision

/** 双档 rec 级联器（快档必选，精档可选）。 */
internal class TieredTextRecognizer(
    private val fast: TextRecognizer,
    private val strong: TextRecognizer,
) : TextRecognizer {
    override val ready: Boolean
        get() = fast.ready

    /** 单框识别只走快档；级联升级在批量入口 [recognizeAll]（名额按帧结算）。 */
    override fun recognize(
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine? = fast.recognize(image, box)

    override fun recognizeAll(
        image: VisionImage,
        boxes: List<OcrBox>,
        cascade: RecCascadeOptions,
    ): List<VisualOcrLine?> {
        if (!ready) return boxes.map { null }
        return upgraded(image, boxes, boxes.map { fast.recognize(image, it) }, cascade)
    }

    /** 按档位补精档结果：mobile 原样、server 全量、cascade 只升级低置信行。 */
    private fun upgraded(
        image: VisionImage,
        boxes: List<OcrBox>,
        base: List<VisualOcrLine?>,
        cascade: RecCascadeOptions,
    ): List<VisualOcrLine?> =
        when {
            !strong.ready || cascade.mode == RecCascadeMode.MOBILE -> base
            cascade.mode == RecCascadeMode.SERVER ->
                boxes.mapIndexed { i, box -> strong.recognize(image, box) ?: base[i] }
            else -> {
                val out = base.toMutableList()
                for (i in upgradeSlots(base, cascade)) {
                    strong.recognize(image, boxes[i])?.let { out[i] = it }
                }
                out
            }
        }

    /** 升级名额：置信度越低越优先（null 行按 0），截断到 [RecCascadeOptions.maxUpgrades]。 */
    private fun upgradeSlots(
        base: List<VisualOcrLine?>,
        cascade: RecCascadeOptions,
    ): List<Int> =
        base
            .withIndex()
            .filter { (_, line) -> line == null || line.confidence < cascade.upgradeConf }
            .sortedBy { (_, line) -> line?.confidence ?: 0.0 }
            .take(cascade.maxUpgrades)
            .map { it.index }
}
