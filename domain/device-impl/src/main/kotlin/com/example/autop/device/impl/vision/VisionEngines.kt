/**
 * 视觉解析推理端口（docs/design/visual-screen-parsing §4）：检测、OCR 与状态分类引擎的内部接口。
 * 未就绪（模型缺失/加载失败）时 [ready] 为 false，编排层回退现有行为。
 */
package com.example.autop.device.impl.vision

/** 控件检测引擎（YOLO）。框几何可信、类别标签为弱先验；叶子逐框 OCR/图标不依赖标签。 */
internal interface VisualDetector {
    /** 模型是否可用（assets 缺失/加载失败/labels_version 不认识为 false）。 */
    val ready: Boolean

    /** 单类兜底档（GPA 权重，无 labels_version 声明）：出框需经 SubtypeRefiner 细化。 */
    val singleClass: Boolean

    /** 部署点置信度阈值（v2 metadata `deploy_conf`）；单类档为 null（用配置 minConfidence）。 */
    val deployConf: Double?

    /** 检测控件框；未就绪返回空列表，推理异常向上抛（WP7.6：失败不伪装成空结果）。 */
    fun detect(image: VisionImage): List<VisualDetection>
}

/** 文本识别器（PP-OCR rec，逐叶子裁剪识别；长行内部切窗拼接）。 */
internal interface TextRecognizer {
    /** 模型与字符集是否可用（类数校验失败为 false）。 */
    val ready: Boolean

    /** 永久停用原因（WP7.6，如字典与模型不符）；未停用为 null。 */
    val disabledReason: String?
        get() = null

    /** 识别单个文本框；未就绪/推理失败/空文本返回 null。 */
    fun recognize(
        image: VisionImage,
        box: OcrBox,
    ): VisualOcrLine?
}

/** 开关状态分类器（state_cls）：勾选类框（checkbox/radio/switch）→ checked 三态。 */
internal interface VisualStateClassifier {
    /** 模型是否可用。 */
    val ready: Boolean

    /** 对勾选类框填充 [VisualDetection.checked]；未就绪原样返回。 */
    fun classify(
        image: VisionImage,
        detections: List<VisualDetection>,
    ): List<VisualDetection>
}

/** 图标分类器（icon_cls）：叶子裁图 → 显示键；拒识/未就绪不出结果。 */
internal interface VisualIconClassifier {
    /** 模型与显示名表是否可用（metadata 校验失败为 false）。 */
    val ready: Boolean

    /** 对候选逐个推理；返回候选索引 → 显示键（拒识候选不出现在结果里）。 */
    fun classify(
        image: VisionImage,
        candidates: List<IconCandidate>,
    ): Map<Int, String>
}
