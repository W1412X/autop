/**
 * 视觉解析推理端口（docs/design/visual-screen-parsing §4）：检测、OCR 与状态分类引擎的内部接口。
 * ONNX 实现见 onnx 包；未就绪（模型缺失/加载失败）时 [ready] 为 false，编排层回退现有行为。
 */
package com.example.autop.device.impl.vision

import com.example.autop.device.Rect

/** 控件检测引擎（单类/多类 YOLO）。 */
internal interface VisualDetector {
    /** 模型是否可用（assets 缺失/加载失败/labels_version 不认识为 false）。 */
    val ready: Boolean

    /** 单类兜底档（GPA 权重，无 labels_version 声明）：出框需经 SubtypeRefiner 细化。 */
    val singleClass: Boolean

    /** 部署点置信度阈值（v2 metadata `deploy_conf`）；单类档为 null（用配置 minConfidence）。 */
    val deployConf: Double?

    /** 检测控件框；未就绪/推理失败返回空列表。 */
    fun detect(image: VisionImage): List<VisualDetection>
}

/** OCR 引擎（PP-OCR det+rec 组合，vp9 §4.4 的 `recognize(image)` 兼容入口）。 */
internal interface VisualOcrEngine {
    /** 模型是否可用。 */
    val ready: Boolean

    /** 识别文本行；未就绪/推理失败返回空列表。 */
    fun recognize(image: VisionImage): List<VisualOcrLine>
}

/** 文本检测器（PP-OCR det，vp9 §4.4 拆分）：任意画布（全图/补扫块）上找文本行框。 */
internal interface TextDetector {
    /** 模型是否可用。 */
    val ready: Boolean

    /**
     * 检测文本行框（画布坐标系）；[region] 非空时只返回框心落在区域内的框
     * （补扫规划的限定范围用，vp9 §4.4）。
     */
    fun detect(
        image: VisionImage,
        region: Rect? = null,
    ): List<OcrBox>
}

/** 文本识别器（PP-OCR rec，vp9 §4.4 拆分）：单框裁剪识别一行文字。 */
internal interface TextRecognizer {
    /** 模型与字符集是否可用（类数校验失败为 false）。 */
    val ready: Boolean

    /** 识别单个文本行；未就绪/推理失败/空文本返回 null。 */
    fun recognize(
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine?
}

/** 开关状态分类器（vp8 §6.3，state_cls）：勾选类框 → checked 三态。 */
internal interface VisualStateClassifier {
    /** 模型是否可用。 */
    val ready: Boolean

    /** 对勾选类框（checkbox/radio/switch）填充 [VisualDetection.checked]；未就绪原样返回。 */
    fun classify(
        image: VisionImage,
        detections: List<VisualDetection>,
    ): List<VisualDetection>
}

/** 图标分类器（vp9 §5，icon_cls）：候选叶子裁图 → 显示键；拒识/未就绪不出结果。 */
internal interface VisualIconClassifier {
    /** 模型与显示名表是否可用（metadata 校验失败为 false，§5.3）。 */
    val ready: Boolean

    /** 对候选逐个推理；返回候选索引 → 显示键（拒识候选不出现在结果里）。 */
    fun classify(
        image: VisionImage,
        candidates: List<IconCandidate>,
    ): Map<Int, String>
}
